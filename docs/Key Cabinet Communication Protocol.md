# Key Cabinet Serial Communication Protocol Specification

**Version**: V1.0  
**Date**: 2025-12-05  
**Scope**: Communication between host computer and key nodes

---

## 1. Basic Parameters

### 1.1 Serial Communication
- **Baud Rate**: 19200
- **Data Bits**: 8
- **Parity**: None (N)
- **Stop Bits**: 1
- **Default Serial Port**: /dev/ttyS1

### 1.2 Ethernet Communication (Applicable to Some Custom Versions)
- **Protocol Type**: TCP Server
- **IP**: DHCP auto-assigned or manually configured
- **Default Port**: 18082

---

## 2. Communication Direction and Frame Delimiters

### 2.1 Host → Key Node (Send Command)
- **Start Byte**: `0x02`
- **End Byte**: `0x04`

### 2.2 Key Node → Host (Return Data)
- **Start Byte**: `0x01`
- **End Byte**: `0x03`

---

## 3. Data Encoding Format (Important)

### 3.1 Byte Splitting Encoding Rules

**All data except the start byte and end byte must be encoded as follows:**

Split each byte into its high nibble and low nibble, converting each half-byte by adding `0x30`.

**Conversion Formula**:
```
Original byte = 0xAB
After conversion = 0x3A, 0x3B
```

**Conversion Examples**:
- `0x01` → `0x30 0x31`
- `0x0F` → `0x30 0x3F`
- `0x12` → `0x31 0x32`
- `0x7F` → `0x37 0x3F`
- `0xAA` → `0x3A 0x3A`
- `0xFF` → `0x3F 0x3F`

### 3.2 Processing Flow

1. **Assemble raw data**: Box Address (1 byte) + Node Address (1 byte) + Command (1 byte) + Data Area (optional) + CRC8 (1 byte)
2. **Calculate CRC8**: Compute CRC8 checksum over the raw data
3. **Split and encode**: Encode all raw data (including CRC8) into two-byte pairs per the above rules
4. **Assemble frame**: Start Byte + Encoded Data + End Byte

---

## 4. Data Frame Structure

### 4.1 Raw Data Frame (Logical Structure, Used for CRC Calculation)

```
Raw Frame = Box Address (1 byte) + Node Address (1 byte) + Command (1 byte) + Data Area (optional) + CRC8 (1 byte)
```

### 4.2 Physical Transmission Frame

```
Transmission Frame = Start Byte (1) + Box Address (2) + Node Address (2) + Command (2) + Data Area (encoded) + CRC8 (2) + End Byte (1)
```

### 4.3 Field Descriptions

#### Start Byte (1 byte, not split)
- Host sends: `0x02`
- Node returns: `0x01`

#### Box Address (1 raw byte → 2 transmission bytes)
- Identifies the key cabinet number
- **Examples**:
  - Box 1 (0x01) → transmits as `0x30 0x31`
  - Box 15 (0x0F) → transmits as `0x30 0x3F`
- **Current convention**: Default box address is `0x01`

#### Node Address (1 raw byte → 2 transmission bytes)
- Identifies the key node number
- **Examples**:
  - Node 1 (0x01) → transmits as `0x30 0x31`
  - Node 127 (0x7F) → transmits as `0x37 0x3F`

#### Command Byte (1 raw byte → 2 transmission bytes)
- Represents the specific operation command
- **Examples**:
  - Command 0x11 → transmits as `0x31 0x31`
  - Command 0x15 → transmits as `0x31 0x35`

#### Data Area (optional, each byte → 2 transmission bytes)
- Defined per specific command; may be empty or contain card numbers, status values, etc.
- Each raw byte is encoded per the splitting rules

#### CRC8 Checksum (1 raw byte → 2 transmission bytes)
- **Checksum scope**: Raw (unsplit) Box Address + Node Address + Command + Data Area
- **Excludes**: Start byte and end byte
- **Algorithm parameters**:
  - Initial value: 0x00
  - Polynomial: 0x8C (reversed)
  - Final XOR: 0x87
- **Note**: Compute CRC on the raw data first, then split-encode the CRC value as well

#### End Byte (1 byte, not split)
- Host sends: `0x04`
- Node returns: `0x03`

---

## 5. Command List and Detailed Descriptions

### 5.1 Indicator Light Control

#### Command 0x11: Blue Light On

**Function**: Illuminate the blue indicator at the specified node

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x11` (Box Address + Node Address + Command)
- CRC8 calculation on raw data `0x01 0x01 0x11` → `0x2B`
- Complete raw frame: `0x01 0x01 0x11 0x2B`

**Transmission frame after split-encoding** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 31 32 3B 04
```

**Field Breakdown**:
- `02`: Start byte (not split)
- `30 31`: Box Address 0x01, split
- `30 31`: Node Address 0x01, split
- `31 31`: Command 0x11, split
- `32 3B`: CRC8 value 0x2B, split
- `04`: End byte (not split)

---

#### Command 0x12: Blue Light Off

**Function**: Turn off the blue indicator at the specified node

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x12`
- CRC8 calculation: `0xC9`
- Complete raw frame: `0x01 0x01 0x12 0xC9`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 32 3C 39 04
```

**Field Breakdown**:
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 32`: Command 0x12
- `3C 39`: CRC8 value 0xC9
- `04`: End byte

---

#### Command 0x19: Red Light On

**Function**: Illuminate the red indicator at the specified node

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x19`
- CRC8 calculation: `0xE9`
- Complete raw frame: `0x01 0x01 0x19 0xE9`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 39 3E 39 04
```

**Field Breakdown**:
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 39`: Command 0x19
- `3E 39`: CRC8 value 0xE9
- `04`: End byte

---

#### Command 0x1A: Red Light Off

**Function**: Turn off the red indicator at the specified node

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x1A`
- CRC8 calculation: `0x0B`
- Complete raw frame: `0x01 0x01 0x1A 0x0B`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 3A 30 3B 04
```

**Field Breakdown**:
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 3A`: Command 0x1A
- `30 3B`: CRC8 value 0x0B
- `04`: End byte

---

### 5.2 Electromagnet Control

#### Command 0x13: Electromagnet Engage

**Function**: Engage the electromagnet (lock/secure key bolt)

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x13`
- CRC8 calculation: `0x97`
- Complete raw frame: `0x01 0x01 0x13 0x97`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 33 39 37 04
```

**Field Breakdown**:
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 33`: Command 0x13
- `39 37`: CRC8 value 0x97
- `04`: End byte

---

#### Command 0x14: Electromagnet Release

**Function**: Release the electromagnet (unlock)

**Direction**: Host → Node

**Host Data Area**: None

**Data Assembly**:
- Raw data: `0x01 0x01 0x14`
- CRC8 calculation: `0x15`
- Complete raw frame: `0x01 0x01 0x14 0x15`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 34 31 35 04
```

**Field Breakdown**:
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 34`: Command 0x14
- `31 35`: CRC8 value 0x15
- `04`: End byte

---

### 5.3 Card Read/Write

#### Command 0x15: Read Card (Return Card Number)

**Function**: Read card information at the current key bolt position

**Direction**: Host → Node

**Host Data Area**: None

**Node Return Data Area**: Card number (4 bytes)

**Host Sends**:
- Raw data: `0x01 0x01 0x15`
- CRC8 calculation: `0x4A`
- Complete raw frame: `0x01 0x01 0x15 0x4A`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 35 34 3A 04
```

**Node Return Example** (Card=0xAABBCCDD):
- Raw data: `0x01 0x01 0x15 0xAA 0xBB 0xCC 0xDD`
- CRC8 calculation: `0xBF`
- Complete raw frame: `0x01 0x01 0x15 0xAA 0xBB 0xCC 0xDD 0xBF`

**Split-encoded return frame**:

```
Return: 01 30 31 30 31 31 35 3A 3A 3B 3B 3C 3C 3D 3D 3B 3F 03
```

**Return Field Breakdown**:
- `01`: Start byte (not split)
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 35`: Command 0x15
- `3A 3A`: Card byte 1 = 0xAA
- `3B 3B`: Card byte 2 = 0xBB
- `3C 3C`: Card byte 3 = 0xCC
- `3D 3D`: Card byte 4 = 0xDD
- `3B 3F`: CRC8 value 0xBF
- `03`: End byte (not split)

---

### 5.4 Micro Switch Detection

#### Command 0x16: Test Micro Switch (Detect Key Bolt Presence)

**Function**: Detect the micro switch at the corresponding position to determine if a key bolt is present

**Direction**: Host → Node

**Host Data Area**: None

**Node Return Data Area**: 4-byte status value
- Key bolt present: `0x00 0x00 0x00 0x00`
- No key bolt: `0xFF 0xFF 0xFF 0xFF`

**Host Sends**:
- Raw data: `0x01 0x01 0x16`
- CRC8 calculation: `0xA8`
- Complete raw frame: `0x01 0x01 0x16 0xA8`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 36 3A 38 04
```

**Node Return Example (Key bolt present)**:
- Raw data: `0x01 0x01 0x16 0x00 0x00 0x00 0x00`
- CRC8 calculation: `0x6D`
- Complete raw frame: `0x01 0x01 0x16 0x00 0x00 0x00 0x00 0x6D`

**Split-encoded return frame (Key bolt present)**:

```
Return: 01 30 31 30 31 31 36 30 30 30 30 30 30 30 30 36 3D 03
```

**Node Return Example (No key bolt)**:
- Raw data: `0x01 0x01 0x16 0xFF 0xFF 0xFF 0xFF`
- CRC8 calculation: `0xE0`
- Complete raw frame: `0x01 0x01 0x16 0xFF 0xFF 0xFF 0xFF 0xE0`

**Split-encoded return frame (No key bolt)**:

```
Return: 01 30 31 30 31 31 36 3F 3F 3F 3F 3F 3F 3F 3F 3E 30 03
```

**Return Field Breakdown** (Key bolt present):
- `01`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 36`: Command 0x16
- `30 30 30 30 30 30 30 30`: Status value 0x00000000 (8 bytes after splitting)
- `36 3D`: CRC8 value 0x6D
- `03`: End byte

---

#### Command 0x17: Test Micro Switch and Read Card

**Function**: Simultaneously obtain micro switch status and card information

**Direction**: Host → Node

**Host Data Area**: None

**Node Return Data Area**: 4 bytes
- Card present: returns 4-byte card number
- No card, micro switch pressed (key bolt present): `0x00 0x00 0x00 0x00`
- No card, micro switch released (no key bolt): `0xFF 0xFF 0xFF 0xFF`

**Host Sends**:
- Raw data: `0x01 0x01 0x17`
- CRC8 calculation: `0xF6`
- Complete raw frame: `0x01 0x01 0x17 0xF6`

**Transmission frame** (Box=1, Node=1):

```
Send: 02 30 31 30 31 31 37 3F 36 04
```

**Node Return Example 1 (Card present = 0xAABBCCDD)**:
- Raw data: `0x01 0x01 0x17 0xAA 0xBB 0xCC 0xDD`
- CRC8 calculation: `0x3C`
- Complete raw frame: `0x01 0x01 0x17 0xAA 0xBB 0xCC 0xDD 0x3C`

**Split-encoded return frame (Card present)**:

```
Return: 01 30 31 30 31 31 37 3A 3A 3B 3B 3C 3C 3D 3D 33 3C 03
```

**Node Return Example 2 (No card + Key bolt present)**:
- Raw data: `0x01 0x01 0x17 0x00 0x00 0x00 0x00`
- CRC8 calculation: `0xA0`
- Complete raw frame: `0x01 0x01 0x17 0x00 0x00 0x00 0x00 0xA0`

**Split-encoded return frame (No card + Key bolt present)**:

```
Return: 01 30 31 30 31 31 37 30 30 30 30 30 30 30 30 3A 30 03
```

**Node Return Example 3 (No card + No key bolt)**:
- Raw data: `0x01 0x01 0x17 0xFF 0xFF 0xFF 0xFF`
- CRC8 calculation: `0x2D`
- Complete raw frame: `0x01 0x01 0x17 0xFF 0xFF 0xFF 0xFF 0x2D`

**Split-encoded return frame (No card + No key bolt)**:

```
Return: 01 30 31 30 31 31 37 3F 3F 3F 3F 3F 3F 3F 3F 32 3D 03
```

**Return Field Breakdown** (Card present):
- `01`: Start byte
- `30 31`: Box Address 0x01
- `30 31`: Node Address 0x01
- `31 37`: Command 0x17
- `3A 3A 3B 3B 3C 3C 3D 3D`: Card number 0xAABBCCDD (4 bytes → 8 bytes after splitting)
- `33 3C`: CRC8 value 0x3C
- `03`: End byte

---

### 5.5 Door Control

#### Command 0x22: Check Key Cabinet Door Status

**Function**: Detect the current status of the key cabinet door

**Direction**: Host → Node

**Host Data Area**: None

**Node Return Data Area**: 4-byte status value
- Door engaged (locked): `0x00 0x00 0x00 0x00`
- Door closed / not engaged: `0xFF 0xFF 0xFF 0xFF`

**Note**: Door detection typically uses Node Address 0

**Host Sends** (Box=1, Node=0):
- Raw data: `0x01 0x00 0x22`
- CRC8 calculation: `0xB3`
- Complete raw frame: `0x01 0x00 0x22 0xB3`

**Transmission frame**:

```
Send: 02 30 31 30 30 32 32 3B 33 04
```

**Node Return Example 1 (Door engaged)**:
- Raw data: `0x01 0x00 0x22 0x00 0x00 0x00 0x00`
- CRC8 calculation: `0xC1`
- Complete raw frame: `0x01 0x00 0x22 0x00 0x00 0x00 0x00 0xC1`

**Split-encoded return frame (Door engaged)**:

```
Return: 01 30 31 30 30 32 32 30 30 30 30 30 30 30 30 3C 31 03
```

**Node Return Example 2 (Door closed / not engaged)**:
- Raw data: `0x01 0x00 0x22 0xFF 0xFF 0xFF 0xFF`
- CRC8 calculation: `0x4C`
- Complete raw frame: `0x01 0x00 0x22 0xFF 0xFF 0xFF 0xFF 0x4C`

**Split-encoded return frame (Door closed)**:

```
Return: 01 30 31 30 30 32 32 3F 3F 3F 3F 3F 3F 3F 3F 34 3C 03
```

**Field Breakdown** (Send):
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 30`: Node Address 0x00 (door)
- `32 32`: Command 0x22
- `3B 33`: CRC8 value 0xB3
- `04`: End byte

---

#### Command 0x23: Eject Door

**Function**: Control the door to eject (door mechanism action)

**Direction**: Host → Node

**Host Data Area**: None

**Node Return**: Execution result acknowledgment

**Host Sends** (Box=1, Node=0):
- Raw data: `0x01 0x00 0x23`
- CRC8 calculation: `0xED`
- Complete raw frame: `0x01 0x00 0x23 0xED`

**Transmission frame**:

```
Send: 02 30 31 30 30 32 33 3E 3D 04
```

**Node Return**:
- Raw data: `0x01 0x00 0x23`
- CRC8 calculation: `0xED`
- Complete raw frame: `0x01 0x00 0x23 0xED`

**Split-encoded return frame**:

```
Return: 01 30 31 30 30 32 33 3E 3D 03
```

**Field Breakdown** (Send):
- `02`: Start byte
- `30 31`: Box Address 0x01
- `30 30`: Node Address 0x00 (door)
- `32 33`: Command 0x23
- `3E 3D`: CRC8 value 0xED
- `04`: End byte

---

## 6. CRC8 Checksum Algorithm Details

### 6.1 Algorithm Parameters

- **Initial Value**: 0x00
- **Polynomial**: 0x8C (reversed)
- **Final XOR**: 0x87
- **Input Reflection**: None
- **Output Reflection**: None

### 6.2 Algorithm Steps

1. Initialize CRC value to 0x00
2. For each raw data byte (unsplit):
   - XOR the CRC with this byte
   - Loop 8 times:
     - If the lowest bit of CRC is 0, shift right by 1
     - If the lowest bit of CRC is 1, shift right by 1 then XOR with 0x8C
3. Finally XOR CRC with 0x87 to obtain the checksum value

### 6.3 Java Reference Implementation

Reference implementation from `Acrc8Utils.java`:

```java
public static int Acrc8(int[] dataInt, int length, int preval) {
    byte crc = (byte)preval;        // Initial value
    for(int j=0; j<length; j++) {
        crc ^= (byte)dataInt[j];
        for (int i = 0; i < 8; i++) {
            if ((crc & 1) == 0)
                crc = (byte)((crc & 0xff) >> 1);
            else
                crc = (byte)(((crc & 0xff) >> 1) ^ 0x8C);
        }
    }
    return (0xff & crc) ^ 0x87;
}
```

### 6.4 Checksum Scope (Important)

**CRC8 is calculated over the raw (unsplit) data**

CRC8 covers:
- Box Address (1 raw byte)
- Node Address (1 raw byte)
- Command byte (1 raw byte)
- Data Area (if any, raw bytes)

**Excludes**:
- Start byte
- CRC8 field itself
- End byte

**Processing order**:
1. Calculate CRC8 over the raw data
2. Split-encode all data (including CRC8)
3. Assemble into the final transmission frame

### 6.5 Calculation Example

Using the Blue Light On command as an example:

**Raw data** (for CRC calculation): `0x01 0x01 0x11`

**Calculation process**:
1. Initial CRC = 0x00
2. Process each byte in order: 0x01, 0x01, 0x11
3. Perform 8 bit-operation cycles per byte
4. Final XOR with 0x87
5. Result: 0x2B

**Split-encode**:
- 0x01 → 0x30 0x31
- 0x01 → 0x30 0x31
- 0x11 → 0x31 0x31
- 0x2B → 0x32 0x3B

**Final transmission frame**: `02 30 31 30 31 31 31 32 3B 04`

---

## 7. Communication Notes

### 7.1 Address Conventions
- In the current version, the default Box Address is `0x01`
- Door-related commands (0x22, 0x23) typically use Node Address `0x00`
- Key node addresses are determined by actual hardware configuration, range 1-127

### 7.2 Data Encoding Notes
- **All data (except start/end bytes) must be split-encoded**
- Splitting rule: split 1 byte into high nibble and low nibble, add 0x30 to each
- Example: 0x1F → 0x31 0x3F
- Encoded data range: 0x30-0x3F (ASCII characters '0' through '?')

### 7.3 CRC Calculation Notes
- **CRC must be calculated on raw data (before splitting)**
- After CRC calculation, the CRC value itself must also be split-encoded
- Checksum scope: Box Address + Node Address + Command + Data Area (all raw values)

### 7.4 Timeout and Retry
- Recommended communication timeout: 500 ms
- Retry 2-3 times on no response
- Log and alert on repeated failures

### 7.5 Error Handling
- After receiving data, first de-split (restore encoded data to raw data)
- Verify CRC8 checksum
- Discard frames with failed checksum verification
- Discard frames with mismatched start/end bytes

### 7.6 Data Parsing Flow
1. Check start byte (0x01 or 0x02)
2. Extract encoded data (excluding start/end bytes)
3. De-split encoded data into raw data (merge every 2 bytes into 1 byte)
4. Extract CRC and verify
5. Parse address, command, and data area

---

## 8. Appendix

### 8.1 Complete Command List

| Code | Name | Direction | Raw Data Area | Transmission Data Area (Split) | Description |
|------|------|-----------|---------------|-------------------------------|-------------|
| 0x11 | Blue Light On | Host→Node | None | None | Illuminate blue indicator |
| 0x12 | Blue Light Off | Host→Node | None | None | Turn off blue indicator |
| 0x13 | Magnet Engage | Host→Node | None | None | Lock key bolt |
| 0x14 | Magnet Release | Host→Node | None | None | Release key bolt |
| 0x15 | Read Card | Bidirectional | 4 bytes card | 8 bytes (split) | Return card info |
| 0x16 | Test Micro Switch | Bidirectional | 4 bytes status | 8 bytes (split) | Detect key bolt presence |
| 0x17 | Micro Switch + Card | Bidirectional | 4 bytes | 8 bytes (split) | Combined detection |
| 0x19 | Red Light On | Host→Node | None | None | Illuminate red indicator |
| 0x1A | Red Light Off | Host→Node | None | None | Turn off red indicator |
| 0x22 | Check Door Status | Bidirectional | 4 bytes status | 8 bytes (split) | Detect door lock state |
| 0x23 | Eject Door | Host→Node | None | None | Control door open |

### 8.2 Data Encoding Lookup Table

| Raw Nibble | Encoded Byte | ASCII Char |
|------------|-------------|------------|
| 0x0 | 0x30 | '0' |
| 0x1 | 0x31 | '1' |
| 0x2 | 0x32 | '2' |
| 0x3 | 0x33 | '3' |
| 0x4 | 0x34 | '4' |
| 0x5 | 0x35 | '5' |
| 0x6 | 0x36 | '6' |
| 0x7 | 0x37 | '7' |
| 0x8 | 0x38 | '8' |
| 0x9 | 0x39 | '9' |
| 0xA | 0x3A | ':' |
| 0xB | 0x3B | ';' |
| 0xC | 0x3C | '<' |
| 0xD | 0x3D | '=' |
| 0xE | 0x3E | '>' |
| 0xF | 0x3F | '?' |

### 8.3 Common Data Encoding Examples

| Raw (1 byte) | Split (2 bytes) | Notes |
|-------------|-----------------|-------|
| 0x00 | 0x30 0x30 | Box Address 0 |
| 0x01 | 0x30 0x31 | Box/Node Address 1 |
| 0x0F | 0x30 0x3F | Box Address 15 |
| 0x7F | 0x37 0x3F | Node Address 127 |
| 0x11 | 0x31 0x31 | Blue Light On command |
| 0x15 | 0x31 0x35 | Read Card command |
| 0xAA | 0x3A 0x3A | Card byte example |
| 0xFF | 0x3F 0x3F | Status: No key bolt |

### 8.4 Frame Length Reference

| Command Type | Raw Frame Length | Transmission Frame Length | Formula |
|-------------|-----------------|--------------------------|---------|
| No-data command (e.g. 0x11) | 4 bytes | 10 bytes | 1(start) + 4×2(addr/cmd) + 1×2(CRC) + 1(end) |
| 4-byte data command (e.g. 0x15 return) | 8 bytes | 18 bytes | 1(start) + 7×2(addr/cmd/data) + 1×2(CRC) + 1(end) |

### 8.5 De-split Algorithm Example

**Restore encoded data to raw data**:

```java
// Combine two encoded bytes back into one raw byte
public static byte decode(byte high, byte low) {
    int h = (high - 0x30) & 0x0F;  // Subtract 0x30 to get high nibble
    int l = (low - 0x30) & 0x0F;   // Subtract 0x30 to get low nibble
    return (byte)((h << 4) | l);   // Merge into one byte
}

// Example
byte[] encoded = {0x3A, 0x3B};  // Encoded data
byte original = decode(encoded[0], encoded[1]);  // 0xAB
```

---

## 9. Card Reader Communication Protocol

### 9.1 Overview

The card reader is an **external device** independent from the key node main controller, communicating with the host via a dedicated serial port. It reads user card numbers (e.g., M1 cards) in the card-swipe area. This is a **different card-reading mechanism** from the key node commands 0x15/0x17:

| Comparison | Node Read Card (0x15/0x17) | Card Reader |
|------------|---------------------------|-------------|
| Serial Port | /dev/ttyS1 (shared with main controller) | **/dev/ttyS2** (dedicated) |
| Baud Rate | 19200 | **9600** |
| Protocol | Split-encoded frames (02...04) | **ASCII plaintext protocol** |
| Scenario | Key node position reading | Public card-swipe area |

### 9.2 Hardware Parameters

| Parameter | Value |
|-----------|-------|
| Serial Port | /dev/ttyS2 |
| Baud Rate | 9600 |
| Data Bits | 8 |
| Parity | None (N) |
| Stop Bits | 1 |
| Reader Model | Maichong / JVS compatible |

### 9.3 Communication Mode

The card reader is a **passive device** and will not actively push data. The host must **periodically send polling commands**; the card reader returns card information only when a card is detected.

```
Host                       Card Reader
  |                            |
  |--- 02 AF DD (poll) ------>|
  |                            |  (no card: no response)
  |<-- (timeout / no data) ----|
  |                            |
  |--- 02 AF DD (poll) ------>|
  |                            |  (card detected: returns ASCII card number)
  |<-- "AABBCCDD\r\n" ---------|
  |                            |
```

- **Polling interval**: Send poll command approximately every 300 ms
- **After card detected**: Pause 1.5 seconds before resuming polling to avoid duplicate triggers

### 9.4 Polling Command

The host sends a fixed 3-byte polling command:

```
02 AF DD
```

| Byte | Value | Description |
|------|-------|-------------|
| Byte 1 | `0x02` | Frame header |
| Byte 2 | `0xAF` | Card search command |
| Byte 3 | `0xDD` | Frame footer |

### 9.5 Card Reader Response

When a card is detected, the reader returns card data in **ASCII plaintext format**.

#### Response Format

```
<ASCII hex card number>\r\n
```

- Returned raw bytes are ASCII-encoded; parse directly as an ASCII string
- Content is a hexadecimal card number, may contain non-hex characters such as `\r`, `\n`
- Card number length depends on card type; M1 cards are typically 8 hex digits

#### Response Example

Raw bytes returned by reader (shown in hex):
```
41 41 42 42 43 43 44 44 0D 0A
```

Parsed as ASCII string:
```
AABBCCDD\r\n
```

After filtering out non-hex characters:
```
AABBCCDD
```

### 9.6 Card Number Parsing Flow

```
Raw bytes
  │
  ▼
ASCII decode: new String(data, "ASCII").trim()
  │  → "AABBCCDD"
  ▼
Filter non-hex chars: replaceAll("[^0-9A-Fa-f]", "")
  │  → "AABBCCDD"
  ▼
Uppercase
  │  → "AABBCCDD"
  ▼
Take last 8 characters (M1 standard length)
  │  → "AABBCCDD"
  ▼
Output card number
```

#### Java Reference Implementation

```java
private static String parseReaderCardAscii(byte[] data) {
    if (data == null || data.length == 0) return null;
    try {
        // ASCII decode
        String asciiStr = new String(data, "ASCII").trim();
        // Filter non-hex characters
        String hexChars = asciiStr.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hexChars.length() >= 8) {
            // Take last 8 characters
            String base8 = hexChars.length() > 8
                ? hexChars.substring(hexChars.length() - 8) : hexChars;
            String b1 = base8.substring(0, 2);
            String b2 = base8.substring(2, 4);
            String b3 = base8.substring(4, 6);
            String b4 = base8.substring(6, 8);
            return b1 + b2 + b3 + b4;
        }
        return hexChars;
    } catch (Exception e) {
        return null;
    }
}
```

### 9.7 Card Reader Monitor Thread Reference Implementation

```java
// Open serial port
SerialPortManager readerSerialPortManager = new SerialPortManager(device);
InputStream in = readerSerialPortManager.mInputStream;
OutputStream out = readerSerialPortManager.mOutputStream;

// Polling command
byte[] pollCmd = new byte[]{0x02, (byte) 0xAF, (byte) 0xDD};
byte[] buf = new byte[512];

while (running) {
    try {
        // 1. Send polling command
        out.write(pollCmd);

        // 2. Wait for hardware response (~300 ms)
        Thread.sleep(300);

        // 3. Check for incoming data
        if (in.available() > 0) {
            int n = in.read(buf);
            if (n > 0) {
                byte[] data = new byte[n];
                System.arraycopy(buf, 0, data, 0, n);
                String card = parseReaderCardAscii(data);
                // Output card number...
                // 4. Pause 1.5s after card read to avoid duplicate triggers
                Thread.sleep(1500);
            }
        }
    } catch (InterruptedException e) {
        break;
    } catch (IOException e) {
        // Communication error, retry after 1 second
        Thread.sleep(1000);
    }
}
```

### 9.8 Differences from Node Card Read Commands

| Feature | Card Reader (/dev/ttyS2) | Node Read Card 0x15 |
|---------|-------------------------|---------------------|
| Trigger | **Active polling** (periodic poll commands) | Host sends command |
| Data Format | ASCII plaintext | Split-encoded frame (nibble + 0x30) |
| Frame Structure | `02 AF DD` → ASCII card number | `02` encoded data `04` → `01` encoded data `03` |
| CRC Check | None | CRC8 (polynomial 0x8C) |
| Response Timing | Only when card present; silent otherwise | Every command has a response |
| Connected To | Card reader module | Key node MCU |

---

## 10. Usage Guide

### 10.1 Hardware Connections

```
┌─────────────────────────────────────────────────┐
│                Host (Android Board)              │
│                                                 │
│  /dev/ttyS1 ──── Key Cabinet Main Controller    │
│  Baud: 19200   (Node Communication)              │
│                                                 │
│  /dev/ttyS2 ──── Card Reader Module              │
│  Baud: 9600                                     │
└─────────────────────────────────────────────────┘
```

### 10.2 Application Startup Flow

1. **Set Key Cabinet Serial Port**: Click "Set KeyBox Serial", defaults to `/dev/ttyS1`, baud `19200`. Once set, communication with key nodes is enabled.
2. **Set Card Reader Serial Port**: Click "Set Reader Serial", defaults to `/dev/ttyS2`, baud `9600`. Once set, the card reader monitor thread starts automatically.
3. **Enter Address**: Input the target node address in the text field (0-indexed).
4. **Click Action Button**: Select the desired operation (Blue Light On, Read Card, Open Door, etc.). Results appear in the log area after execution.

### 10.3 Card Swiping Flow

1. Click "Set Reader Serial" to confirm card reader serial port parameters
2. Log area shows "Reader serial set"; the card reader monitor thread starts automatically
3. Place an M1 card in the card-swipe area
4. Log area displays the raw data received from the reader along with the decoded card number

### 10.4 Notes

- Only one electromagnet may be opened at a time (key operations must be performed one by one); otherwise insufficient current will cause failure
- The card reader monitor thread starts automatically after setting the serial port and stops automatically when the application exits
- The card reader and node card reading (0x15) are two independent card-reading mechanisms and must not be mixed
- Node addresses are **0-indexed**; door detection typically uses node address 0

---

**End of Document**
