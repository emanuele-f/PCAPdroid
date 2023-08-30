#ifndef __PCAPDROID_ERRORS_H__
#define __PCAPDROID_ERRORS_H__

// This file contains a set of error strings which may be returned from the native code.
// These should be translated in CaptureService.reportError

#define PD_ERR_INVALID_PCAP_FILE        "Invalid PCAP file"
#define PD_ERR_INTERFACE_OPEN_ERROR     "Could not open the capture interface"
#define PD_ERR_UNSUPPORTED_DATALINK     "Unsupported datalink"
#define PD_ERR_PCAP_DOES_NOT_EXIST      "The specified PCAP file does not exist"
#define PD_ERR_PCAPD_START              "pcapd daemon start failure"
#define PD_ERR_PCAPD_NOT_SPAWNED        "pcapd daemon did not spawn"
#define PD_ERR_PCAP_READ                "PCAP read error"

#endif