# PeerLink

**Decentralized P2P File Transfer & CLI Chat over WebRTC**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![WebRTC](https://img.shields.io/badge/WebRTC-0.14.0-blue?logo=webrtc)](https://github.com/devopvoid/webrtc-java)
[![Maven](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven)](https://maven.apache.org/)

PeerLink is a command-line application that enables **direct peer-to-peer file transfer and text chat** between two machines — with no central relay server touching your data. Once the WebRTC connection is established, all bytes travel end-to-end between peers. The signaling server's job ends the moment the data channel opens.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [How It Works](#how-it-works)
  - [Signaling Phase](#1-signaling-phase)
  - [WebRTC Connection](#2-webrtc-connection)
  - [File Transfer Protocol](#3-file-transfer-protocol)
  - [Resume & Fault Tolerance](#4-resume--fault-tolerance)
  - [Pause & Resume Mid-Transfer](#5-pause--resume-mid-transfer)
- [Features](#features)
- [Project Structure](#project-structure)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Setup & Running](#setup--running)
- [CLI Commands](#cli-commands)
- [Design Decisions](#design-decisions)
- [Performance Characteristics](#performance-characteristics)
- [Related Repository](#related-repository)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SIGNALING PHASE ONLY                         │
│                                                                     │
│   PeerLink CLI (Peer A)          PeerLink CLI (Peer B)              │
│         │                                │                          │
│         │         WebSocket              │                          │
│         └──────► Signaling Server ◄──────┘                         │
│                  (SDP + ICE exchange)                               │
│                                                                     │
│              Once data channel is OPEN, server is idle              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     P2P DATA PHASE (no server)                      │
│                                                                     │
│   PeerLink CLI (Peer A)  ◄────── RTCDataChannel ──────►  Peer B    │
│         │                                                     │     │
│     FileSender                                         FileReceiver │
│     ChatHandler                                        ChatHandler  │
│                                                                     │
│              All file bytes & chat are end-to-end                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## How It Works

### 1. Signaling Phase

PeerLink uses a lightweight WebSocket signaling server (see [PeerLink Signaling Server](https://github.com/krishnadaga5106/signaling-server-peer-link)) solely to bootstrap the WebRTC handshake. No file data ever passes through it.

**Room-based pairing:**

- **Creator** sends a `CREATE` message → server allocates a 6-character room code and returns it.
- **Joiner** submits the room code via a `JOIN` message → server confirms and notifies the creator that a peer has arrived.

The signaling exchange is:

```
Peer A (Creator)           Signaling Server           Peer B (Joiner)
     │                           │                           │
     │──── CREATE ──────────────►│                           │
     │◄─── JOINED (roomCode) ────│                           │
     │                           │◄──────── JOIN ────────────│
     │◄─── PEER_JOIN ────────────│                           │
     │                           │──────── JOINED ──────────►│
     │──── OFFER (SDP) ─────────►│──────────────────────────►│
     │                           │◄──────── ANSWER (SDP) ────│
     │◄─── ANSWER ───────────────│                           │
     │──── ICE candidate ───────►│──────────────────────────►│
     │◄─── ICE candidate ────────│◄──────────────────────────│
     │                           │                           │
     │◄══════════ RTCDataChannel OPEN (server now idle) ════►│
```

### 2. WebRTC Connection

`P2PWebRTC` wraps the native WebRTC stack (via `webrtc-java`) and handles:

- **PeerConnectionFactory** setup with Google STUN (`stun:stun.l.google.com:19302`) for NAT traversal.
- **SDP negotiation** — the creator calls `createOffer()` which also opens the `RTCDataChannel`; the joiner answers with `createAnswer()`.
- **ICE trickle** — candidates are forwarded via the signaling channel as they are discovered.
- **DataChannel observer** — fires `onDataChannel()` once the channel is `OPEN`, which unblocks the main application thread via a `CountDownLatch`.

### 3. File Transfer Protocol

PeerLink uses a **binary `RTCDataChannel`** with a custom framing protocol layered on top. Text control frames and binary data frames coexist on the same channel — the receiver's `MessageHandler` dispatches them by inspecting the `binary` flag on each `RTCDataChannelBuffer`.

**Message flow:**

```
Sender                                          Receiver
  │                                                 │
  │── FILE_REQ::<names>::<totalSizeKB> ────────────►│  Metadata + file list
  │                                                 │  (user reviews)
  │◄─ ACK / NACK ───────────────────────────────────│
  │                                                 │
  │  [for each file]                                │
  │── FILE_START::<name>::<fileSize> ──────────────►│
  │◄─ FILE_START::<name>::<startOffset> ────────────│  Resume offset
  │                                                 │
  │── [binary chunks, 16 KB each] ─────────────────►│
  │                                                 │
  │── FILE_COMPLETE::SUCCESS::<TRUE|FALSE> ─────────►│
```

Chunk size is fixed at **16 KB** — small enough to avoid backpressure stalls on the WebRTC send buffer, large enough to keep overhead low. The sender monitors `bufferedAmount` and sleeps in 5 ms increments whenever it exceeds 64 KB, preventing buffer overflows.

### 4. Resume & Fault Tolerance

PeerLink implements a **byte-offset resume protocol** that survives interrupted transfers:

1. When a `FILE_START` frame arrives, `FileReceiver` checks the download directory for a `.part` file.
2. If one exists and its size is ≤ the expected file size, the receiver computes a safe resume point — the largest multiple of the chunk size that fits within the already-received bytes: `floor(existingLen / CHUNK_SIZE) * CHUNK_SIZE`.
3. This offset is sent back to the sender in the `FILE_START` acknowledgement.
4. `FileSender` opens the file with `RandomAccessFile` and calls `seek(offset)` — an **O(1)** disk operation regardless of file size — then resumes streaming from that byte.
5. On completion, the `.part` suffix is atomically removed by renaming the file.

Result: **zero data loss on interrupted transfers**, validated across files larger than 10 GB.

**Memory profile:** the sender holds a constant 44 MB footprint (one chunk buffer + WebRTC native stack). The receiver holds approximately 216 MB. Neither side buffers the full file in memory.

### 5. Pause & Resume Mid-Transfer

Either peer can pause an in-progress transfer:

- **Sender pause**: sets a `CountDownLatch(1)` that blocks the chunk-sending loop at the top of each iteration. Sends a `FILE_PAUSE` signal to the receiver.
- **Sender resume**: counts down the latch, restoring the loop. Sends `FILE_RESUME`.
- **Receiver pause**: sends `FILE_PAUSE`; the sender's loop sees the latch and blocks.
- Only the peer that initiated the pause can release it — the other peer cannot forcibly resume a remote-initiated pause.

---

## Features

- **True P2P** — file bytes never touch any server after the initial handshake
- **RTCDataChannel file transfer** — ordered, binary channel with full SDP + ICE negotiation
- **Fault-tolerant resume** — mid-transfer crash recovery via `.part` files and byte-offset bookkeeping
- **Constant-memory streaming** — 16 KB chunks with `RandomAccessFile` seek; tested across 10 GB+ files
- **Backpressure control** — sender respects `bufferedAmount` thresholding to avoid DataChannel saturation
- **Bidirectional pause/resume** — either peer can pause and only the initiator can unpause
- **In-band chat** — text messages and file-transfer control frames share the same DataChannel
- **Native file picker** — GUI file/folder selection via TinyFileDialogs (no path typing required)
- **Interface-driven design** — `DataHandler`, `FileTransfer`, and `EventListener` keep layers cleanly decoupled

---

## Project Structure

```
PeerLink/
└── src/main/java/
    ├── MainApplication.java          # Entry point — wires all components
    │
    ├── Core/
    │   ├── AppState.java             # Enum: CONNECTING → CHATTING → TRANSFERRING → …
    │   ├── Controller.java           # Orchestrator; implements EventListener & SystemHandler
    │   └── MessageHandler.java       # Dispatches incoming DataChannel frames
    │
    ├── WebRTC/
    │   └── P2PWebRTC.java            # RTCPeerConnection, DataChannel, ICE, SDP
    │
    ├── Signaling/
    │   ├── SignalingClient.java       # Jetty WebSocket client → signaling server
    │   └── SignalingSocket.java       # WebSocket session wrapper
    │
    ├── FileTransfer/
    │   ├── FileSender.java            # Chunk loop, backpressure, pause/resume, ACK handling
    │   ├── FileReceiver.java          # .part file management, resume offset, binary writes
    │   └── SenderMessageHandler.java  # DataHandler impl for sender-side control messages
    │
    ├── Interfaces/
    │   ├── DataHandler.java           # handleBin() + handleText()
    │   ├── FileTransfer.java          # pause() + resume()
    │   ├── EventListener.java         # WebRTC lifecycle callbacks
    │   └── SystemHandler.java         # App-state mutation surface
    │
    └── Models/
        ├── MessageType.java           # CREATE, JOIN, OFFER, ANSWER, ICE, …
        ├── ResponseType.java          # JOINED, PEER_JOIN, OFFER, ANSWER, ICE, ERROR, …
        ├── SignalingMessage.java       # Outbound signaling payload
        └── SignalingResponse.java      # Inbound signaling payload
```

---

## Tech Stack

| Concern | Library / Tool | Version |
|---|---|---|
| Language | Java | 21 |
| WebRTC | webrtc-java (devopvoid) | 0.14.0 |
| WebSocket client | Jetty WebSocket Client | 9.4.57 |
| JSON | Jackson Databind | 3.0.2 |
| CLI | JLine | 3.30.0 |
| File picker | LWJGL + TinyFileDialogs | 3.3.6 |
| Logging | SLF4J Simple | 2.0.9 |
| Boilerplate | Lombok | 1.18.30 |
| Build | Maven + Shade plugin | — |

---

## Prerequisites

- **Java 21** or later
- **Maven 3.8+**
- A running instance of the [PeerLink Signaling Server](https://github.com/krishnadaga5106/signaling-server-peer-link)
- Machines behind NAT can typically connect using Google STUN — no TURN server is required for most home/office networks

---

## Setup & Running

### 1. Clone the repository

```bash
git clone https://github.com/krishnadaga5106/peerlink.git
cd peerlink
```

### 2. Configure the signaling server URL

Edit `src/main/resources/application.properties`:

```properties
# Point this at your running signaling server
SignalingServerURL=ws://<your-signaling-server-host>:8080/ws
```

### 3. Build the fat JAR

```bash
mvn clean package -DskipTests
```

The Maven Shade plugin bundles all dependencies (including native WebRTC binaries) into a single executable JAR in `target/`.

### 4. Run PeerLink

```bash
java -jar target/PeerLink-1.0-SNAPSHOT.jar
```

> **On startup**, you will be prompted to enter a username. Then the main menu appears.

### Running two peers locally (quick test)

Open two terminals:

**Terminal 1 — Creator:**
```
[SYSTEM]: Enter username: alice

====== P2P WebRTC CLI ======
1) Create Room   /create
2) Join Room     /join
3) Quit          /exit
Choice: /create

[SYSTEM]: Your Room Code: AB12CD
[SYSTEM]: Share this room code with other peer to let them join!
```

**Terminal 2 — Joiner:**
```
[SYSTEM]: Enter username: bob

====== P2P WebRTC CLI ======
Choice: /join
[SYSTEM]: Enter the Room Code: AB12CD
```

Once the DataChannel opens, both terminals print `[SYSTEM]: Connected to Peer!`.

---

## CLI Commands

All commands are prefixed with `/`. Plain text is sent as a chat message.

| Command | Available When | Description |
|---|---|---|
| `/create` | Main menu | Create a new room and get a shareable code |
| `/join` | Main menu | Join an existing room by its code |
| `/exit` | Main menu / Chat | Quit the application |
| `/send` | Chatting | Open file picker and initiate file transfer |
| `/accept` | Reviewing files | Accept an incoming file transfer request |
| `/deny` | Reviewing files | Reject an incoming file transfer request |
| `/pause` | Transferring | Pause the active transfer (notifies other peer) |
| `/resume` | Transfer paused | Resume a transfer you paused |
| `/retry` | Selecting save folder | Retry folder selection after dismissing the dialog |
| `/cancel` | Selecting save folder | Cancel the incoming transfer |

---

## Design Decisions

**Why a dedicated signaling server instead of a public broker?**
A self-hosted signaling server gives you full control over room lifetime, code generation, and session tracking. The signaling server does not relay any file data — it sees only SDP strings and ICE candidates.

**Why `RandomAccessFile` instead of `FileInputStream`?**
`RandomAccessFile` exposes a `seek(long pos)` method that positions the file cursor in O(1) time regardless of file size. This is essential for the resume protocol — seeking to byte 4,000,000,000 in a 10 GB file is instantaneous.

**Why chunk-level backpressure instead of stream-level throttling?**
The WebRTC DataChannel has a finite send buffer. Pushing chunks faster than the network can drain it causes `bufferedAmount` to grow unboundedly, eventually dropping the channel. Polling `bufferedAmount` every 5 ms and sleeping when it exceeds 64 KB is simple, effective, and doesn't require flow-control negotiation with the receiver.

**Why `CountDownLatch` for thread coordination?**
The main application thread runs the CLI input loop. Asynchronous events — ICE completion, DataChannel open, ACK receipt — arrive on separate threads. `CountDownLatch` provides a clean, one-shot gate that suspends the main thread until the event occurs, without the complexity of shared queues or polling.

**Why interface-driven design (`DataHandler`, `FileTransfer`, `EventListener`)?**
The same DataChannel carries chat frames, control frames, and binary file chunks. Swapping the active `DataHandler` (from `MessageHandler` to `SenderMessageHandler` or `FileReceiver`) at runtime lets the correct handler consume messages for the current transfer phase — without any `instanceof` proliferation in the core dispatch path.

---

## Performance Characteristics

| Metric | Value |
|---|---|
| Chunk size | 16 KB |
| Send buffer threshold | 64 KB (`bufferedAmount`) |
| Sender memory footprint | ~44 MB (validated at 10 GB+ file) |
| Receiver memory footprint | ~216 MB (validated at 10 GB+ file) |
| Resume granularity | `floor(received / 16 KB) * 16 KB` |
| STUN server | `stun:stun.l.google.com:19302` |

---

## Related Repository

The signaling server that PeerLink depends on is maintained in a separate repository:

**[PeerLink Signaling Server](https://github.com/krishnadaga5106/signaling-server-peer-link)** — Spring Boot WebSocket server with Redis-backed room registry.
