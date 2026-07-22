# eKMS Key Cabinet Communication Protocol

**Status:** Reference for Step 4 (Keys, cabinet slots and access grants)

**Audience:** Backend developer, Website developer, Terminal developer

**Source:** `terminalApp/src/main/java/com/ekms/terminal/hardware/KeyCabinetProtocol.kt`,
`shared/src/commonMain/kotlin/com/ekms/shared/domain/SiteTerminalManagement.kt`,
`shared/src/commonMain/kotlin/com/ekms/shared/domain/KeySlotManagement.kt`

## Purpose

This document is the single reference for the **Box Address + Node Address**
addressing scheme used everywhere a key, cabinet slot or terminal is
registered, validated or displayed. Every module (Terminal, Website, Mobile,
backend) must use the same two numbers to identify one physical key peg — no
module may invent its own offset or shorthand.

## Physical addressing

| Term | Range | Meaning |
|---|---:|---|
| Box Address | 1–255 | Identifies one physical cabinet unit on the serial bus. One eKMS `Terminal` record owns exactly one Box Address (`Terminal.boxAddress`). |
| Node Address | 0–255 | Identifies one physical position inside that cabinet. Node `0` is always the cabinet **door** and is never a key slot. Key nodes are `1..configuredSlotCount`, where `configuredSlotCount` is the terminal's registered key-node capacity (`Terminal.configuredSlotCount`). |

A physical key is therefore located by the pair **(Box Address, Node
Address)** — in the domain model this is **(Terminal, `KeySlot.nodeAddress`)**,
since the Box Address is a property of the Terminal the slot belongs to.
Node addresses are raw protocol values: no module may silently add or
subtract 1 when displaying or storing them.

## Serial frame protocol (Terminal-only)

Implemented in `KeyCabinetProtocol.kt`. Only the Android Terminal's hardware
layer may open the cabinet serial port and use this framing; Website and
Mobile never construct or receive a raw frame.

- Transport: `/dev/ttyS1` (default), 19,200 baud (default) — see
  `SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT` /
  `DEFAULT_CABINET_BAUD_RATE`.
- Host → cabinet frames are delimited by `0x02` … `0x04`.
- Cabinet → host reply frames are delimited by `0x01` … `0x03`.
- Every payload byte (Box Address, Node Address, command, CRC8) is
  transmitted using a split-nibble encoding: each raw byte becomes two ASCII
  bytes, `0x30 + high nibble` then `0x30 + low nibble`.
  `KeyCabinetProtocol.buildRequestFrame` / `parseResponse` implement the
  encode/decode.
  - Encoded 4-byte payload: `[boxAddress, nodeAddress, command, crc8]`.
- CRC8 uses the supplier polynomial `0x8C` with a final `xor 0x87` — see
  `KeyCabinetProtocol.calculateCrc8`. A reply is rejected if the box address,
  node address or command in the decoded reply does not match the request, or
  if the CRC does not match.
- Key-node commands (0x11–0x1A, 0x15–0x17) require a node address in
  `0..255`; door commands (`checkDoorStatus`, `ejectDoor`) always address
  node `0` internally (`KeyCabinetProtocol.DOOR_NODE_ADDRESS`).

## Software validation layers

Three independent layers apply the same addressing rules, from cabinet
registration down to a single key:

1. **Terminal registration** — `SiteTerminalManagementPolicy` (shared)
   validates Box Address (`MIN_BOX_ADDRESS..MAX_BOX_ADDRESS`, i.e. 1–255) and
   configured key-node capacity (`MIN_NODE_ADDRESS..MAX_NODE_ADDRESS`) when a
   Super Admin creates or edits a `Terminal`.
2. **Key-slot mapping** — `KeySlotAccessPolicy.validateSlot` (shared)
   validates that a `KeySlot.nodeAddress` is an integer in
   `KeySlotAccessPolicy.MIN_KEY_NODE_ADDRESS..terminal.configuredSlotCount`
   (never `DOOR_NODE_ADDRESS`, 0), that the terminal has no other active slot
   at that node, and that an assigned key belongs to the same site as the
   terminal. This is the check Website and Mobile use, since they never see
   the serial protocol directly.
3. **Physical enrollment** — `TerminalAdminStore.createKey` (Android
   Terminal-only) re-validates Box Address and raw Node Address at the
   moment a physical fob is enrolled, immediately before it is bound to a
   `TerminalKey`/`KeySlot` record, and rejects a duplicate Box+Node pair.

## What each module may and may not do

- Website and Mobile may read and validate Box Address / Node Address
  numbers, and may create/edit a `KeySlot` mapping, but they never open the
  serial port and never send a frame.
- Only the signed-in Android Terminal may perform the physical actions
  behind a node address (blue/red light, electromagnet engage/release, fob
  read, door eject).
- A key-node address is always the raw supplier value. No module applies a
  hidden `-1`/`+1` conversion between the UI and the wire protocol.
