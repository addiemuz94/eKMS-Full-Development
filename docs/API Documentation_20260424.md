# API Documentation

**Introduction**: API Documentation

**HOST**: 127.0.0.1:8082

**Contact**:

**Version**: 1.2

**Base Path**: undefined

[TOC]

# Upload File

## Photo Upload (Demo test, used to demonstrate third-party app interface format)

**Endpoint**: `/file/upload`

**Method**: `POST`

**Content-Type**: `multipart/form-data`

**Response-Type**: `*/*`

**Description**: <p>Third-party applications need to develop the interface for receiving records according to this format. When login photo uploading or taking/returning video functions are enabled, the key cabinet terminal will call this interface to upload picture or video records.</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| uploadFile | uploadFile | formData | false | file | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

# Synchronize Data - Third-Party General Interface (converts http api to websocket protocol for communicating with key cabinet terminal)

## Modify Access Information

**Endpoint**: `/syncDirectWs/appendAccess`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Add or modify access information based on user number</p>

**Request Example**:

```javascript
{
  "terminalUuid": "",
  "userKeyAccessSyncDtoList": [
    {
      "keyNumberStrList": [],
      "userNumberStr": ""
    }
  ]
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| userKeyAccessSyncDircetParam | userKeyAccessSyncDircetParam | body | true | UserKeyAccessSyncDircetParam | UserKeyAccessSyncDircetParam |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |
| &emsp;&emsp;userKeyAccessSyncDtoList | Access information | | true | array | UserKeyAccessSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;keyNumberStrList | Available key numbers (app terminal) | | true | array | string |
| &emsp;&emsp;&emsp;&emsp;userNumberStr | User number (app terminal) | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Append Appointment Information

**Endpoint**: `/syncDirectWs/appendAppointment`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Add or modify appointment information based on application number</p>

**Request Example**:

```javascript
{
  "appointmentSyncDircetDtoList": [
    {
      "appointmentNumberStr": "",
      "endDateTime": 0,
      "keyNumberStr": "",
      "startDateTime": 0,
      "userNumberStr": ""
    }
  ],
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| appointmentSyncDirectParam | appointmentSyncDirectParam | body | true | AppointmentSyncDirectParam | AppointmentSyncDirectParam |
| &emsp;&emsp;appointmentSyncDircetDtoList | Appointment information | | true | array | AppointmentSyncDircetDto |
| &emsp;&emsp;&emsp;&emsp;appointmentNumberStr | Appointment number (Primary key) | | true | string | |
| &emsp;&emsp;&emsp;&emsp;endDateTime | End time | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;keyNumberStr | Key number | | true | string | |
| &emsp;&emsp;&emsp;&emsp;startDateTime | Start time | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;userNumberStr | User number | | true | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Modify Key Information

**Endpoint**: `/syncDirectWs/appendKey`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Add or modify key information based on key location</p>

**Request Example**:

```javascript
{
  "keySyncDirectDtoList": [
    {
      "card": "",
      "expiry": 0,
      "name": "",
      "nodeAddr": 0,
      "numberStr": ""
    }
  ],
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| keySyncDirectParam | keySyncDirectParam | body | true | KeySyncDirectParam | KeySyncDirectParam |
| &emsp;&emsp;keySyncDirectDtoList | Key information | | true | array | KeySyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;card | Key card number | | false | string | |
| &emsp;&emsp;&emsp;&emsp;expiry | Usable time limit (ms timestamp) | | false | integer | |
| &emsp;&emsp;&emsp;&emsp;name | Key name | | false | string | |
| &emsp;&emsp;&emsp;&emsp;nodeAddr | Key address (starts from 0, app terminal primary key) | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;numberStr | Key number | | false | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Modify User Information

**Endpoint**: `/syncDirectWs/appendUser`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Add or modify user information based on user number</p>

**Request Example**:

```javascript
{
  "terminalUuid": "",
  "userSyncDirectDtoList": [
    {
      "adminType": 0,
      "card": "",
      "faceList": [],
      "facePhoto": "",
      "fingerList": [],
      "name": "",
      "numberStr": "",
      "password": ""
    }
  ]
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| userSyncDirectParam | userSyncDirectParam | body | true | UserSyncDirectParam | UserSyncDirectParam |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |
| &emsp;&emsp;userSyncDirectDtoList | User information | | true | array | UserSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;adminType | User type: 0->Operator; 1->Admin; (Admin can log into key cabinet management backend) | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;card | User card number | | false | string | |
| &emsp;&emsp;&emsp;&emsp;faceList | Face info (for face recognition login) | | false | array | string |
| &emsp;&emsp;&emsp;&emsp;facePhoto | User photo URL (if faceList is empty, download operation auto-extracts features to faceList) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;fingerList | Fingerprint info (for fingerprint login) | | false | array | string |
| &emsp;&emsp;&emsp;&emsp;name | User name | | false | string | |
| &emsp;&emsp;&emsp;&emsp;numberStr | User number (app terminal primary key) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;password | User password | | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Verify Face

**Endpoint**: `/syncDirectWs/checkUserFace`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Control the specified key cabinet terminal to extract face features via face photo url. 'msg' returns description, 'data' returns face features</p>

**Request Example**:

```javascript
{
  "facePhotoUrl": "",
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| checkUserFaceSyncDirectParam | checkUserFaceSyncDirectParam | body | true | CheckUserFaceSyncDirectParam | CheckUserFaceSyncDirectParam |
| &emsp;&emsp;facePhotoUrl | Face photo URL | | true | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Delete Access Information

**Endpoint**: `/syncDirectWs/deleteAccess`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Delete access information based on user number</p>

**Request Example**:

```javascript
{
  "terminalUuid": "",
  "userKeyAccessSyncDtoList": [
    {
      "keyNumberStrList": [],
      "userNumberStr": ""
    }
  ]
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| userKeyAccessSyncDircetParam | userKeyAccessSyncDircetParam | body | true | UserKeyAccessSyncDircetParam | UserKeyAccessSyncDircetParam |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |
| &emsp;&emsp;userKeyAccessSyncDtoList | Access information | | true | array | UserKeyAccessSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;keyNumberStrList | Available key numbers (app terminal) | | true | array | string |
| &emsp;&emsp;&emsp;&emsp;userNumberStr | User number (app terminal) | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Delete Appointment Information

**Endpoint**: `/syncDirectWs/deleteAppointment`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Delete appointment information based on application number</p>

**Request Example**:

```javascript
{
  "appointmentSyncDircetDtoList": [
    {
      "appointmentNumberStr": "",
      "endDateTime": 0,
      "keyNumberStr": "",
      "startDateTime": 0,
      "userNumberStr": ""
    }
  ],
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| appointmentSyncDirectParam | appointmentSyncDirectParam | body | true | AppointmentSyncDirectParam | AppointmentSyncDirectParam |
| &emsp;&emsp;appointmentSyncDircetDtoList | Appointment information | | true | array | AppointmentSyncDircetDto |
| &emsp;&emsp;&emsp;&emsp;appointmentNumberStr | Appointment number (Primary key) | | true | string | |
| &emsp;&emsp;&emsp;&emsp;endDateTime | End time | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;keyNumberStr | Key number | | true | string | |
| &emsp;&emsp;&emsp;&emsp;startDateTime | Start time | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;userNumberStr | User number | | true | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Delete Key Information

**Endpoint**: `/syncDirectWs/deleteKey`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Delete key information based on key location</p>

**Request Example**:

```javascript
{
  "keySyncDirectDtoList": [
    {
      "card": "",
      "expiry": 0,
      "name": "",
      "nodeAddr": 0,
      "numberStr": ""
    }
  ],
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| keySyncDirectParam | keySyncDirectParam | body | true | KeySyncDirectParam | KeySyncDirectParam |
| &emsp;&emsp;keySyncDirectDtoList | Key information | | true | array | KeySyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;card | Key card number | | false | string | |
| &emsp;&emsp;&emsp;&emsp;expiry | Usable time limit (ms timestamp) | | false | integer | |
| &emsp;&emsp;&emsp;&emsp;name | Key name | | false | string | |
| &emsp;&emsp;&emsp;&emsp;nodeAddr | Key address (starts from 0, app terminal primary key) | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;numberStr | Key number | | false | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Delete User Information

**Endpoint**: `/syncDirectWs/deleteUser`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Delete user information based on user number</p>

**Request Example**:

```javascript
{
  "terminalUuid": "",
  "userSyncDirectDtoList": [
    {
      "adminType": 0,
      "card": "",
      "faceList": [],
      "facePhoto": "",
      "fingerList": [],
      "name": "",
      "numberStr": "",
      "password": ""
    }
  ]
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| userSyncDirectParam | userSyncDirectParam | body | true | UserSyncDirectParam | UserSyncDirectParam |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |
| &emsp;&emsp;userSyncDirectDtoList | User information | | true | array | UserSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;adminType | User type: 0->Operator; 1->Admin; (Admin can log into key cabinet management backend) | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;card | User card number | | false | string | |
| &emsp;&emsp;&emsp;&emsp;faceList | Face info (for face recognition login) | | false | array | string |
| &emsp;&emsp;&emsp;&emsp;facePhoto | User photo URL | | false | string | |
| &emsp;&emsp;&emsp;&emsp;fingerList | Fingerprint info (for fingerprint login) | | false | array | string |
| &emsp;&emsp;&emsp;&emsp;name | User name | | false | string | |
| &emsp;&emsp;&emsp;&emsp;numberStr | User number (app terminal primary key) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;password | User password | | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Query Terminal List

**Endpoint**: `/syncDirectWs/listTerminal`

**Method**: `GET`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Query terminal list</p>

**Request Parameters**:

None

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«List«string»» |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | array | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": [],
	"message": ""
}
```

## Read Access Information

**Endpoint**: `/syncDirectWs/readAccess`

**Method**: `GET`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Read access permissions of usable keys for personnel in the designated terminal. This access is non-permanent, granting a person the right to take a key at a specific location</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«List«UserKeyAccessSyncDirectDto»» |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | array | UserKeyAccessSyncDirectDto |
| &emsp;&emsp;keyNumberStrList | Usable key numbers (app terminal) | array | string |
| &emsp;&emsp;userNumberStr | User number (app terminal) | string | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": [
		{
			"keyNumberStrList": [],
			"userNumberStr": ""
		}
	],
	"message": ""
}
```

## Read Appointment Information

**Endpoint**: `/syncDirectWs/readAppointment`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Read personnel appointment information in the specified terminal. Appointment permission is a one-time temporary permission, allowing a designated person to take a specific key within a certain time period</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«List«AppointmentSyncDircetDto»» |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | array | AppointmentSyncDircetDto |
| &emsp;&emsp;appointmentNumberStr | Appointment number (Primary key) | string | |
| &emsp;&emsp;endDateTime | End time | integer(int64) | |
| &emsp;&emsp;keyNumberStr | Key number | string | |
| &emsp;&emsp;startDateTime | Start time | integer(int64) | |
| &emsp;&emsp;userNumberStr | User number | string | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": [
		{
			"appointmentNumberStr": "",
			"endDateTime": 0,
			"keyNumberStr": "",
			"startDateTime": 0,
			"userNumberStr": ""
		}
	],
	"message": ""
}
```

## Read Key Information

**Endpoint**: `/syncDirectWs/readKey`

**Method**: `GET`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Read key detailed information, including name, card number, location, etc.</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«List«KeySyncDirectDto»» |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | array | KeySyncDirectDto |
| &emsp;&emsp;card | Key card number | string | |
| &emsp;&emsp;expiry | Usable time limit (ms timestamp) | integer(int64) | |
| &emsp;&emsp;name | Key name | string | |
| &emsp;&emsp;nodeAddr | Key address (starts from 0, app terminal primary key) | integer(int32) | |
| &emsp;&emsp;numberStr | Key number | string | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": [
		{
			"card": "",
			"expiry": 0,
			"name": "",
			"nodeAddr": 0,
			"numberStr": ""
		}
	],
	"message": ""
}
```

## Query Node Status

**Endpoint**: `/syncDirectWs/readKeyStateGetResult`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Real-time query for key cabinet online status and whether keys have been taken</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«KeyStateSyncResult» |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | KeyStateSyncResult | KeyStateSyncResult |
| &emsp;&emsp;keyStateList | Key status info | array | KeyState |
| &emsp;&emsp;&emsp;&emsp;nodeAddr | Key location | integer | |
| &emsp;&emsp;&emsp;&emsp;state | Key status: 0->Not in place; 1->In place; | integer | |
| &emsp;&emsp;result | Terminal status: 0->Offline; 1->Online; | integer(int32) | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {
		"keyStateList": [
			{
				"nodeAddr": 0,
				"state": 0
			}
		],
		"result": 0
	},
	"message": ""
}
```

## Read Terminal Information

**Endpoint**: `/syncDirectWs/readTerminal`

**Method**: `GET`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Read terminal detailed information, including terminal name, super admin password, return key authentication mode, etc.</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«TerminalSyncDirectDto» |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | TerminalSyncDirectDto | TerminalSyncDirectDto |
| &emsp;&emsp;name | Name | string | |
| &emsp;&emsp;password | Super admin password (for logging into key cabinet management backend) | string | |
| &emsp;&emsp;type | Type: 0->Embedded terminal; 1->Android terminal; 2->Commercial version | integer(int32) | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {
		"name": "",
		"password": "",
		"type": 0
	},
	"message": ""
}
```

## Read User Information

**Endpoint**: `/syncDirectWs/readUser`

**Method**: `GET`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Read user detailed information, including name, card number, password, fingerprint info, face info, user type, etc.</p>

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalUuid | Terminal Unique ID | query | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult«List«UserSyncDirectDto»» |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | array | UserSyncDirectDto |
| &emsp;&emsp;adminType | User type: 0->Operator; 1->Admin; (Admin can log into key cabinet management backend) | integer(int32) | |
| &emsp;&emsp;card | User card number | string | |
| &emsp;&emsp;faceList | Face info (for face recognition login) | array | string |
| &emsp;&emsp;facePhoto | User photo URL (if faceList is empty, download operation auto-extracts features to faceList) | string | |
| &emsp;&emsp;fingerList | Fingerprint info (for fingerprint login) | array | string |
| &emsp;&emsp;name | User name | string | |
| &emsp;&emsp;numberStr | User number (app terminal primary key) | string | |
| &emsp;&emsp;password | User password | string | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": [
		{
			"adminType": 0,
			"card": "",
			"faceList": [],
			"facePhoto": "",
			"fingerList": [],
			"name": "",
			"numberStr": "",
			"password": ""
		}
	],
	"message": ""
}
```

## Remote Take Key

**Endpoint**: `/syncDirectWs/remoteTake`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Directly control the key cabinet to release the specified key. This function differs from remote authorization; the key cabinet will only respond when in standby mode</p>

**Request Example**:

```javascript
{
  "keyNodeAddrList": [],
  "terminalUuid": "",
  "userNumberStr": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| remoteTakeSyncDirectParam | remoteTakeSyncDirectParam | body | true | RemoteTakeSyncDirectParam | RemoteTakeSyncDirectParam |
| &emsp;&emsp;keyNodeAddrList | Remote take key address | | true | array | integer |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |
| &emsp;&emsp;userNumberStr | User number | | false | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Write Terminal Information

**Endpoint**: `/syncDirectWs/writeTerminal`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Modify terminal information, including terminal name, super admin password, return key authentication mode, etc.</p>

**Request Example**:

```javascript
{
  "terminalSyncDirectDto": {
    "name": "",
    "password": "",
    "type": 0
  },
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| terminalSyncDirectParam | terminalSyncDirectParam | body | true | TerminalSyncDirectParam | TerminalSyncDirectParam |
| &emsp;&emsp;terminalSyncDirectDto | Terminal information | | true | TerminalSyncDirectDto | TerminalSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;name | Name | | false | string | |
| &emsp;&emsp;&emsp;&emsp;password | Super admin password (for logging into key cabinet management backend) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;type | Type: 0->Embedded terminal; 1->Android terminal; 2->Commercial version | | false | integer | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

## Write Terminal Banner

**Endpoint**: `/syncDirectWs/writeTerminalBanner`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: 

**Request Example**:

```javascript
{
  "bannerUrls": "",
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| bannerSyncDirectParam | bannerSyncDirectParam | body | true | BannerSyncDirectParam | BannerSyncDirectParam |
| &emsp;&emsp;bannerUrls | JSON list of banner photo URLs, e.g.: [{"url":"http://192.168.1.90:8082/file/banner1"},{"url":"http://192.168.1.90:8082/file/banner2"}]. When empty, custom banners are deleted and restored to factory defaults | | true | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```

# Third-Party Push Interface

## Push Record (Demo test, used to demonstrate third-party app interface format)

**Endpoint**: `/push/postRecord`

**Method**: `POST`

**Content-Type**: `application/json`

**Response-Type**: `*/*`

**Description**: <p>Third-party applications must develop the interface for receiving records according to this format. After receiving a record, the key system service program will call the interface provided by the third-party application to complete the record push</p>

**Request Example**:

```javascript
{
  "recordSyncDirectDto": {
    "alcoholValue": "",
    "appointmentNumberStr": "",
    "attachment": "",
    "keyNodeAddr": 0,
    "keyNumberStr": "",
    "recordDateTime": 0,
    "type": 0,
    "userNumberStr": ""
  },
  "terminalUuid": ""
}
```

**Request Parameters**:

| Parameter Name | Description | Request Type | Required | Data Type | Schema |
| -------- | -------- | ----- | -------- | -------- | ------ |
| recordSyncDirectDtoParam | recordSyncDirectDtoParam | body | true | RecordSyncDirectDtoParam | RecordSyncDirectDtoParam |
| &emsp;&emsp;recordSyncDirectDto | Record information | | true | RecordSyncDirectDto | RecordSyncDirectDto |
| &emsp;&emsp;&emsp;&emsp;alcoholValue | Alcohol test result | | false | string | |
| &emsp;&emsp;&emsp;&emsp;appointmentNumberStr | Appointment number (Returned if taken via appointment permission) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;attachment | Attachment (video, photo) | | false | string | |
| &emsp;&emsp;&emsp;&emsp;keyNodeAddr | Key address (for compatibility debugging, unused) | | false | integer | |
| &emsp;&emsp;&emsp;&emsp;keyNumberStr | Key number | | false | string | |
| &emsp;&emsp;&emsp;&emsp;recordDateTime | Record time | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;type | Record type: 0->Take; 1->Return; 2->Open door; 3->Close door; 4->Illegal door open; 5->Illegal door close; 7->Boot record; 8->Admin settings; 14->Emergency open cabinet door; 15->Emergency close cabinet door; 18->Self-check complete; 23->Timeout unreturned alarm; 24->Login (photo); 28->Node fault; 29->Node recovery; 30->Password continuously entered wrong; 31->Alcohol test result | | true | integer | |
| &emsp;&emsp;&emsp;&emsp;userNumberStr | User number | | false | string | |
| &emsp;&emsp;terminalUuid | Terminal ID | | true | string | |

**Response Status**:

| Status Code | Description | Schema |
| -------- | -------- | ----- | 
| 200 | OK | CommonResult |
| 201 | Created | |
| 401 | Unauthorized | |
| 403 | Forbidden | |
| 404 | Not Found | |

**Response Parameters**:

| Parameter Name | Description | Type | Schema |
| -------- | -------- | ----- |----- | 
| code | Return value: 200->Success; 500->Failed | integer(int64) | integer(int64) |
| data | Return data | object | |
| message | Return description | string | |

**Response Example**:
```javascript
{
	"code": 0,
	"data": {},
	"message": ""
}
```
