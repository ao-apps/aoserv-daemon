//
// aoserv-daemon - Server management daemon for the AOServ Platform.
// Copyright (C) 2013, 2020, 2022  AO Industries, Inc.
//     support@aoindustries.com
//     7262 Bull Pen Cir
//     Mobile, AL 36695
//
// This file is part of aoserv-daemon.
//
// aoserv-daemon is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// aoserv-daemon is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
//

// Compile with:
// gcc -O3 -Winline --param large-function-growth=500 --param inline-unit-growth=150 --param max-inline-insns-single=700 -lpcap -o ../bin/ip_counts ip_counts.c

#define __STDC_FORMAT_MACROS
#include <inttypes.h>
#include <errno.h>
#include <pcap.h>
#include <stdlib.h> 
#include <stdint.h> 
#include <stdio.h> 
#include <string.h>
#include <strings.h>
#include <time.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>

#undef DEBUG
#undef ASSERT

// TODO: Can we switch away from root user once libpcap descriptor open?

// Preamble + Start of frame + CRC + Interframe gap
#define FRAME_ADDITIONAL_BYTES (7 + 1 + 4 + 12)

// The beginning number of networks to track
#define START_NETWORKS_LEN 16

// Values used in parsing

// The number of bytes in a MAC address
#define MAC_ADDRESS_LEN 6
// The offset to payload start (no VLAN tagging)
#define FRAME_PAYLOAD_START (MAC_ADDRESS_LEN + MAC_ADDRESS_LEN + 0 + 2)

// Capture settings.  Only need the minimum IPv4 header
#define CAPTURE_BYTES (FRAME_PAYLOAD_START + sizeof(struct ip))
#define READ_TIMEOUT 100

// The maximum number of characters in an IPv4 address + '\0'
#define IPV4_MAX_STRING_LEN (4 + 1 + 4 + 1 + 4 + 1 + 4 + 1)

// The number of seconds between warnings
#define WARNING_INTERVAL 10

typedef enum { FALSE, TRUE } boolean;

typedef enum { SOURCE, DESTINATION } src_or_dst;

typedef enum { TEXT, BINARY } output_type;

struct sample {
  int64_t start;
  int64_t end;
};

// Gets the delta value for a sample
inline int64_t getSampleDelta(const struct sample sample) {
  return sample.end - sample.start;
}

struct counts {
  struct sample packets;
  struct sample bytes;
};

struct protocol_counts {
  struct counts icmp_counts;
  struct counts udp_counts;
  struct counts tcp_counts;
  struct counts other_counts;
};

struct ipv4_network {
  struct in_addr network;
  // Between 0 and 32
  uint8_t prefix;
  struct in_addr netmask;
  struct in_addr hostmask;
  struct protocol_counts total_counts;
  // Has 1 << (32-prefix) entries
  struct protocol_counts* ips;
};

void printWarning(const char* const call, const char* const errbuf) {
  if (strnlen(errbuf, PCAP_ERRBUF_SIZE)!=0) {
    fprintf(stderr, "WARNING: %s: %s\n", call, errbuf);
  }
}
void printError(const char* const call, const char* const errbuf) {
  fprintf(stderr, "ERROR: %s: %s\n", call, errbuf);
}
void printErrno(const char* const call, const int errsv) {
  printError(call, strerror(errsv));
}

// Adds to a counts struct end values
inline void addCounts(struct counts* counts, const bpf_u_int32 physicalLen) {
  counts->packets.end++;
  counts->bytes.end += physicalLen;
}

// Adds counts on a per-protocol basis
inline void addProtocolCounts(struct protocol_counts* counts, const u_int8_t protocol, const bpf_u_int32 physicalLen) {
  switch (protocol) {
    case IPPROTO_ICMP :
      addCounts(&counts->icmp_counts, physicalLen);
      break;
    case IPPROTO_UDP :
      addCounts(&counts->udp_counts, physicalLen);
      break;
    case IPPROTO_TCP :
      addCounts(&counts->tcp_counts, physicalLen);
      break;
    default :
      addCounts(&counts->other_counts, physicalLen);
  }
}

#ifdef ASSERT
inline void addCheckSample(struct sample* total_sample, const struct sample sample) {
  total_sample->start += sample.start;
  total_sample->end   += sample.end;
}

inline void addCheckCounts(struct counts* total_check, const struct counts counts) {
  addCheckSample(&total_check->packets, counts.packets);
  addCheckSample(&total_check->bytes,   counts.bytes);
}

inline void addCheckProtocolCounts(struct counts* total_check, const struct protocol_counts* const counts) {
  addCheckCounts(total_check, counts->icmp_counts);
  addCheckCounts(total_check, counts->udp_counts);
  addCheckCounts(total_check, counts->tcp_counts);
  addCheckCounts(total_check, counts->other_counts);
}

inline void addCheckNetworkCounts(struct protocol_counts* network_check, const struct protocol_counts* const counts) {
  addCheckCounts(&network_check->icmp_counts, counts->icmp_counts);
  addCheckCounts(&network_check->udp_counts, counts->udp_counts);
  addCheckCounts(&network_check->tcp_counts, counts->tcp_counts);
  addCheckCounts(&network_check->other_counts, counts->other_counts);
}
#endif

// Copies the end value to the start, this is used on each output to calculate the deltas
inline void copyEndToStartSample(struct sample* sample) {
  sample->start = sample->end;
}

// Copies the end value to the start, this is used on each output to calculate the deltas
inline void copyEndToStartCounts(struct counts* counts) {
  copyEndToStartSample(&counts->packets);
  copyEndToStartSample(&counts->bytes);
}

// Copies the end value to the start, this is used on each output to calculate the deltas
inline void copyEndToStartProtocolCounts(struct protocol_counts* counts) {
  copyEndToStartCounts(&counts->icmp_counts);
  copyEndToStartCounts(&counts->udp_counts);
  copyEndToStartCounts(&counts->tcp_counts);
  copyEndToStartCounts(&counts->other_counts);
}

// Reads a file, parsing a single uint64_t.
// Returns 0 on success or -1 on failure.  errno will be set on failure
int readStatsFile(const char* const path, uint64_t* result) {
#ifdef DEBUG
  fprintf(stderr, "readStatsFile: path=%s\n", path);
#endif
  FILE* in = fopen(path, "r");
  if (in==NULL) {
    printErrno("fopen", errno);
    return -1;
  } else {
    int retVal = 0;
    if (fscanf(in, "%"PRIu64, result) != 1) retVal = -1;
    if (fclose(in) != 0) {
      printErrno("fclose", errno);
      retVal = -1;
    }
    return retVal;
  }
}

// Reads interface counts from /sys/class/net/(device)/statistics into the counts end values
// Returns 0 on success or -1 on failure.  errno will be set on failure
int readInterfaceStats(
  const char* const device,
  const pcap_direction_t network_direction,
  struct counts* counts,
  struct sample* dropped,
  struct sample* errors,
  struct sample* fifo_errors
) {
  int deviceLen = strlen(device);
  char* path = (char*)malloc(
    (
      15           // Length of "/sys/class/net/"
      + deviceLen  // Length of device
      + 12         // Length of "/statistics/"
      + 3          // Length of "rx_" or "tx_"
      + 11         // Longest of "bytes", "packets", "dropped", "errors", "fifo_errors"
      + 1          // Trailing null character
    ) * sizeof(char)
  );
  if (path == NULL) {
    printErrno("malloc", errno);
    return -1;
  } else {
    // Determine sys directory path
    strncpy(path, "/sys/class/net/", 15);
    strncpy(path+15, device, deviceLen);
    strncpy(path+15+deviceLen, "/statistics/", 12);
    strncpy(path+15+deviceLen+12, network_direction==PCAP_D_IN ? "rx_" : "tx_", 3);
    // Read packets file
    strcpy(path+15+deviceLen+12+3, "packets");
    int result = readStatsFile(path, &counts->packets.end);
    if (result == 0) {
      // Read bytes file
      strcpy(path+15+deviceLen+12+3, "bytes");
      result = readStatsFile(path, &counts->bytes.end);
      if (result == 0) {
        // Add additional bytes for Ethernet frame overhead per packet
        counts->bytes.end += counts->packets.end * FRAME_ADDITIONAL_BYTES;
        // Read dropped file
        strcpy(path+15+deviceLen+12+3, "dropped");
        result = readStatsFile(path, &dropped->end);
        if (result == 0) {
          // Read errors file
          strcpy(path+15+deviceLen+12+3, "errors");
          result = readStatsFile(path, &errors->end);
          if (result == 0) {
            // Read fifo_errors file
            strcpy(path+15+deviceLen+12+3, "fifo_errors");
            result = readStatsFile(path, &fifo_errors->end);
          }
        }
      }
    }
    free(path);
    return result;
  }
}

// Parses an IPv4 network, returning TRUE on success
// Allocates the per-IP counters
int parse_ipv4_network(struct ipv4_network* network, const char* str) {
  // Find slash (/)
  char* slashPos = index(str, '/');
  if (slashPos != NULL) {
    if ((slashPos-str) < IPV4_MAX_STRING_LEN) {
      // Parse prefix between 0-32
      char* endptr = NULL;
      errno = 0;
      long int prefix = strtol(slashPos+1, &endptr, 10);
      if (errno==0 && endptr!=NULL && (slashPos+1)!=endptr && endptr[0]=='\0' && prefix>=0 && prefix<=32) {
        network->prefix = prefix;
        // host mask
        network->hostmask.s_addr = prefix==0 ? 0xffffffff : htonl((1<<(32-prefix))-1);
        // net mask
        network->netmask.s_addr = 0xffffffff ^ network->hostmask.s_addr;
#ifdef DEBUG
        fprintf(stderr, "prefix=%i\n", prefix);
        fprintf(stderr, "hostmask=%s\n", inet_ntoa(network->hostmask));
        fprintf(stderr, "netmask=%s\n", inet_ntoa(network->netmask));
#endif
        // Parse inet address
        char addressCopy[IPV4_MAX_STRING_LEN];
        strncpy(addressCopy, str, slashPos-str);
        addressCopy[slashPos-str] = '\0';
#ifdef DEBUG
        fprintf(stderr, "addressCopy=%s\n", addressCopy);
#endif
        if (inet_aton(addressCopy, &network->network)!=0) {
          network->network.s_addr &= network->netmask.s_addr;
#ifdef DEBUG
          fprintf(stderr, "network=%s\n", inet_ntoa(network->network));
#endif
          network->ips = (struct protocol_counts*)calloc(1<<(32-prefix), sizeof(struct protocol_counts));
          if (network->ips == NULL) {
            printErrno("calloc", errno);
            return errno==0 ? ENOMEM : errno;
          }
          return 0;
        }
      }
    }
  }
  fprintf(stderr, "Invalid network: %s\n", str);
  return EINVAL;
}

uint8_t protocol_version;
output_type output;
int retVal = 0;
pcap_t* descr = NULL;

// The last time of the output
struct timeval last_output_time;

// The interface stats
uint64_t ifstats_start_packets;
uint64_t ifstats_start_bytes;
struct counts ifstats_total;
struct sample ifstats_dropped;
struct sample ifstats_errors;
struct sample ifstats_fifo_errors;

// The time of last warning output
struct timeval last_warning_time;

// The device being accessed
char* device;

// The direction of packet flow being captured
pcap_direction_t network_direction;

// The flag controlling whether counting by source or destination addresses
src_or_dst count_direction;

// Handle 32-bit reach-around
struct sample stats_received;
u_int last_stats_received;
struct sample stats_dropped;
u_int last_stats_dropped;

// The total number of packets processed
struct counts total_counts;

// The number of packets that are unparseable and cannot be placed into a more specific count
struct counts unparseable_counts;

// For packets that a parseable but not associated with a tracked network
struct protocol_counts other_network_stats;

// The list of networks is tracked on a per-class-C basis (/24)
// the networks are added when first seen.  Unused networks are never removed.
// This is sufficient for dealing with smaller number of IP addresses.
// To deal with larger numbers, a sorted list and binary search would be better.
int num_networks = 0;
struct ipv4_network* networks = NULL;

// Writes a single byte to stdout, sets retVal to non-zero if an error occurs
inline void writeByte(uint8_t value) {
  if (fwrite(&value, 1, 1, stdout) != 1) {
    printError("fwrite", errno==0 ? "short write" : strerror(errno));
    retVal = errno==0 ? EIO : errno;
  }
}

// Writes a network 32-bit integer to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeNetInt(uint32_t value) {
  if (fwrite(&value, 4, 1, stdout) != 1) {
    printError("fwrite", errno==0 ? "short write" : strerror(errno));
    retVal = errno==0 ? EIO : errno;
  }
}

// Writes a 32-bit value to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeHostInt(uint32_t value) {
  writeNetInt(htonl(value));
}

// Writes a 64-bit value to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeHostLong(uint64_t value) {
  writeNetInt(htonl(value>>32));
  writeNetInt(htonl(value));
}

// Writes the sample to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeSample(struct sample sample) {
  writeHostLong(getSampleDelta(sample));
}

// Writes the counts to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeCounts(struct counts counts) {
  writeSample(counts.packets);
  writeSample(counts.bytes);
}

// Writes the protocol counts to stdout in network byte order, sets retVal to non-zero if an error occurs
inline void writeProtocolCounts(struct protocol_counts* counts) {
  writeCounts(counts->icmp_counts);
  writeCounts(counts->udp_counts);
  writeCounts(counts->tcp_counts);
  writeCounts(counts->other_counts);
}

// Checks if a sample is backwards (end < start)
inline boolean isBackwardSample(const struct sample sample) {
  return sample.end < sample.start;
}
// Checks if a count is backwards
inline boolean isBackwardCount(const struct counts counts) {
  return
    isBackwardSample(counts.packets)
    || isBackwardSample(counts.bytes)
  ;
}

// Adjusts the sample, also updating the total_counts and optionally network_counts
inline void adjustSample(
  int64_t* total_delta,
  int64_t* totalAdjust,
  struct sample* total_counts,
  struct sample* network_counts,
  struct sample* target
) {
  const int64_t target_delta = getSampleDelta(*target);
  if (target_delta != 0) {
    const int64_t total_delta_value = *total_delta;
#ifdef DEBUG
    fprintf(stderr, "target_delta=%"PRIi64"\n", target_delta);
    fprintf(stderr, "total_delta_value=%"PRIi64"\n", total_delta_value);
#endif
#ifdef ASSERT
    if (total_delta_value == 0) {
      fprintf(stderr, "Assertion failed: total_delta_value==0\n");
      exit(1);
    }
#endif
    const int64_t targetAdjust = (*totalAdjust) * target_delta / total_delta_value;
    if (targetAdjust != 0) {
#ifdef DEBUG
      fprintf(stderr, "targetAdjust=%"PRIi64"\n", targetAdjust);
#endif
      target->end       += targetAdjust;
      total_counts->end += targetAdjust;
      if (network_counts != NULL) network_counts->end += targetAdjust;
      (*totalAdjust)    -= targetAdjust;
    }
    (*total_delta) -= target_delta;
  }
}
// Adjusts the counts, also updating the total_counts and optionally network_counts
inline void adjustCounts(
  int64_t* total_delta_packets,
  int64_t* total_delta_bytes,
  int64_t* totalAdjustPackets,
  int64_t* totalAdjustBytes,
  struct counts* total_counts,
  struct counts* network_counts,
  struct counts* target
) {
  adjustSample(total_delta_packets, totalAdjustPackets, &total_counts->packets, network_counts==NULL ? NULL : &network_counts->packets, &target->packets);
  adjustSample(total_delta_bytes,   totalAdjustBytes,   &total_counts->bytes,   network_counts==NULL ? NULL : &network_counts->bytes,   &target->bytes);
}
// Adjusts the protocol_counts, also updating the total_counts and optionally network_counts
inline void adjustProtocolCounts(
  int64_t* total_delta_packets,
  int64_t* total_delta_bytes,
  int64_t* totalAdjustPackets,
  int64_t* totalAdjustBytes,
  struct counts* total_counts,
  struct protocol_counts* network_counts,
  struct protocol_counts* target
) {
  adjustCounts(total_delta_packets, total_delta_bytes, totalAdjustPackets, totalAdjustBytes, total_counts, network_counts==NULL ? NULL : &network_counts->icmp_counts, &target->icmp_counts);
  adjustCounts(total_delta_packets, total_delta_bytes, totalAdjustPackets, totalAdjustBytes, total_counts, network_counts==NULL ? NULL : &network_counts->udp_counts, &target->udp_counts);
  adjustCounts(total_delta_packets, total_delta_bytes, totalAdjustPackets, totalAdjustBytes, total_counts, network_counts==NULL ? NULL : &network_counts->tcp_counts, &target->tcp_counts);
  adjustCounts(total_delta_packets, total_delta_bytes, totalAdjustPackets, totalAdjustBytes, total_counts, network_counts==NULL ? NULL : &network_counts->other_counts, &target->other_counts);
}

void processPacket(u_char* const arg, const struct pcap_pkthdr* const pkthdr, const u_char* const packet) {
  // Fetch starting time
  struct timeval current_time;
  if (gettimeofday(&current_time, NULL) != 0) {
    printErrno("gettimeofday", errno);
    retVal = errno;
    pcap_breakloop(descr);
    return;
  }
  // Check if time went backward
  if (
    current_time.tv_sec < last_output_time.tv_sec
    || (
      current_time.tv_sec==last_output_time.tv_sec
      && current_time.tv_usec < last_output_time.tv_usec
    )
  ) {
    printError("gettimeofday", "Time went backward");
    retVal = EINVAL;
    pcap_breakloop(descr);
    return;
  }
  const bpf_u_int32 frameLen = pkthdr->len;
  const bpf_u_int32 physicalLen = frameLen + FRAME_ADDITIONAL_BYTES;
  const bpf_u_int32 capturedLen = pkthdr->caplen;
#ifdef DEBUG
  fprintf(stderr, "Got packet!\n");
  fprintf(stderr, "  frameLen......: %"PRIu32"\n", frameLen);
  fprintf(stderr, "  physicalLen...: %"PRIu32"\n", physicalLen);
  fprintf(stderr, "  capturedLen...: %"PRIu32"\n", capturedLen);
#endif
  // Parse packet and apply to appropriate stats
  boolean unparseable;

  // Add to total counts
  addCounts(&total_counts, physicalLen);

  // Must have enough bytes to have minimum IPv4 header in the payload
  if (capturedLen >= (FRAME_PAYLOAD_START + sizeof(struct ip))) {
    // Make sure IPv4
    const char payload0 = packet[FRAME_PAYLOAD_START];
    const uint8_t ipVersion = (payload0 & 0xf0) >> 4;
#ifdef DEBUG
    fprintf(stderr, "  ipVersion.....: %hhu\n", ipVersion);
#endif
    if (ipVersion == 4) {
      // Use IPv4 header struct
      struct ip* ipv4Header = (struct ip*)(packet + FRAME_PAYLOAD_START);
      // Parse remaining IPv4 fields
      const u_int8_t protocol = ipv4Header->ip_p;
      const struct in_addr effective_ip = count_direction==SOURCE ? ipv4Header->ip_src : ipv4Header->ip_dst;
#ifdef DEBUG
      fprintf(stderr, "  protocol......: %hhu\n", protocol);
      fprintf(stderr, "  effective_ip..: %s\n", inet_ntoa(effective_ip));
#endif
      unparseable = FALSE;

      // Find network by ip, add counts
      boolean found = FALSE;
      int n;
      struct ipv4_network* networkIter;
      for (
        n = num_networks, networkIter = networks;
        n > 0;
        n--,              networkIter++
      ) {
        if ((effective_ip.s_addr & networkIter->netmask.s_addr)==networkIter->network.s_addr) {
#ifdef DEBUG
          fprintf(stderr, "  network.......: %s/%hhu\n", inet_ntoa(networkIter->network), networkIter->prefix);
#endif
          // Add counts to network totals
          addProtocolCounts(&networkIter->total_counts, protocol, physicalLen);
          // Add counts to specific IP
          addProtocolCounts(
            &networkIter->ips[
              ntohl(effective_ip.s_addr & networkIter->hostmask.s_addr)
            ],
            protocol,
            physicalLen
          );
          found = TRUE;
          break;
        }
      }
      if (!found) {
        //  Output once per second for IPs not in network ranges when not in debug mode
        if (
#ifdef DEBUG
          true
#else
          current_time.tv_sec>=(last_warning_time.tv_sec + WARNING_INTERVAL)
#endif
        ) {
          fprintf(stderr, "Network not found: %s\n", inet_ntoa(effective_ip));
          last_warning_time = current_time;
        }
        // Add counts if no network found
        addProtocolCounts(&other_network_stats, protocol, physicalLen);
      }
    } else {
      // Only IPv4 supported
      unparseable = TRUE;
    }
  } else {
    // Didn't capture minimum IPv4 header
    unparseable = TRUE;
  }

  // Count as unparseable when not able to parse
  if (unparseable) {
    addCounts(&unparseable_counts, physicalLen);
    // Output at most one unparseable per second when in non-debug mode
    if (
#ifdef DEBUG
      true
#else
      current_time.tv_sec>=(last_warning_time.tv_sec + WARNING_INTERVAL)
#endif
    ) {
      fprintf(stderr, "Unparseable: ");
      int i;
      for (i=0; i<capturedLen; i++) {
        fprintf(stderr, "%02X", packet[i]);
      }
      fprintf(stderr, "\n");
      last_warning_time = current_time;
    }
  }
  // Output if current time changed (output once per second)
  if (current_time.tv_sec!=last_output_time.tv_sec) {
    struct timeval start_output_time = last_output_time;
    last_output_time = current_time;
    // Read end ifstats
    if (readInterfaceStats(device, network_direction, &ifstats_total, &ifstats_dropped, &ifstats_errors, &ifstats_fifo_errors) != 0) {
      retVal = errno==0 ? EIO : errno;
      pcap_breakloop(descr);
      return;
    }
    // Check if ifstats went backward
    if (
      isBackwardCount(ifstats_total)
      || isBackwardSample(ifstats_dropped)
      || isBackwardSample(ifstats_errors)
      || isBackwardSample(ifstats_fifo_errors)
    ) {
      printError("readInterfaceStats", "Interface statistics went backward");
      retVal = EINVAL;
      pcap_breakloop(descr);
      return;
    }
    // Get the stats
    errno = 0;
    struct pcap_stat stats;
    if (pcap_stats(descr, &stats) != 0) {
      printError("pcap_stats", pcap_geterr(descr));
      retVal = errno==0 ? ENOSYS : errno;
      pcap_breakloop(descr);
      return;
    }
    // Get packet counts since last iteration
    u_int received = stats.ps_recv - last_stats_received;
    u_int dropped  = stats.ps_drop - last_stats_dropped; // Would we need to add-in dropped from /sys/class/net/(device)/statistics/(direction)_dropped?
    // Handle 32-bit reach-around
    stats_received.end += received;
    stats_dropped.end  += dropped;
    last_stats_received = stats.ps_recv;
    last_stats_dropped  = stats.ps_drop;

    // Keep capture count before extrapolation
    const struct counts captured_counts = total_counts;

    // Add-in dropped packets (or remove extra packets) in proportion to the number of packets per network, IP, and protocol
    int64_t totalAdjustPackets = ifstats_total.packets.end - ifstats_start_packets - total_counts.packets.end;
    int64_t totalAdjustBytes   = ifstats_total.bytes.end   - ifstats_start_bytes   - total_counts.bytes.end;
#ifdef DEBUG
    fprintf(stderr, "totalAdjustPackets=%"PRIi64"\n", totalAdjustPackets);
    fprintf(stderr, "totalAdjustBytes=%"PRIi64"\n",   totalAdjustBytes);
#endif
    if (totalAdjustPackets != 0 || totalAdjustBytes != 0) {
      int64_t total_delta_packets =
        getSampleDelta(total_counts.packets)
      ;
      int64_t total_delta_bytes =
        getSampleDelta(total_counts.bytes)
      ;
#ifdef DEBUG
      fprintf(stderr, "total_delta_packets=%"PRIi64"\n", total_delta_packets);
      fprintf(stderr, "total_delta_bytes=%"PRIi64"\n",   total_delta_bytes);
#endif
      if (total_delta_packets != 0 || total_delta_bytes != 0) {
        // Do not remove counts for more packets than we have received since last output
        if (
          totalAdjustPackets < 0
          && (-totalAdjustPackets) > total_delta_packets
        ) {
          totalAdjustPackets = -total_delta_packets;
#ifdef DEBUG
          fprintf(stderr, "Constrained negative: totalAdjustPackets=%"PRIi64"\n", totalAdjustPackets);
#endif
        }
        // Do not remove counts for more bytes than we have received since last output
        if (
          totalAdjustBytes < 0
          && (-totalAdjustBytes) > total_delta_bytes
        ) {
          totalAdjustBytes = -total_delta_bytes;
#ifdef DEBUG
          fprintf(stderr, "Constrained negative: totalAdjustBytes=%"PRIi64"\n", totalAdjustBytes);
#endif
        }
        // Distribute to unparseable_counts in proportion
        adjustCounts(&total_delta_packets, &total_delta_bytes, &totalAdjustPackets, &totalAdjustBytes, &total_counts, NULL, &unparseable_counts);
        // Distribute to other_network_stats in proportion
        adjustProtocolCounts(&total_delta_packets, &total_delta_bytes, &totalAdjustPackets, &totalAdjustBytes, &total_counts, NULL, &other_network_stats);
        // Adjust each IP in proportion to its packets and bytes
        int n;
        struct ipv4_network* networkIter;
        for (
          n = num_networks, networkIter = networks;
          n > 0;
          n--,              networkIter++
        ) {
          const int num_ips = 1 << (32 - networkIter->prefix);
          int o;
          struct protocol_counts* ipIter;
          for (
            o = num_ips, ipIter = networkIter->ips;
            o > 0;
            o--,         ipIter++
          ) {
            adjustProtocolCounts(&total_delta_packets, &total_delta_bytes, &totalAdjustPackets, &totalAdjustBytes, &total_counts, &networkIter->total_counts, ipIter);
          }
        }
#ifdef ASSERT
        // All must be zero at end
        if (total_delta_packets!=0 || total_delta_bytes!=0 || totalAdjustPackets!=0 || totalAdjustBytes!=0) {
          retVal = EPROTO;
          fprintf(
            stderr,
            "Assertion failed: total_delta_packets!=0 || total_delta_bytes!=0 || totalAdjustPackets!=0 || totalAdjustBytes!=0\n"
            "total_delta_packets=%"PRIi64"\n"
            "total_delta_bytes=%"PRIi64"\n"
            "totalAdjustPackets=%"PRIi64"\n"
            "totalAdjustBytes=%"PRIi64"\n",
            total_delta_packets,
            total_delta_bytes,
            totalAdjustPackets,
            totalAdjustBytes
          );
          pcap_breakloop(descr);
          return;
        }
#endif
      }
    }
    // Write output
    if (protocol_version == 1) {
      if (output == TEXT) {
        struct timeval delta_time;
        if (start_output_time.tv_usec > current_time.tv_usec) {
          delta_time.tv_sec = current_time.tv_sec - start_output_time.tv_sec - 1;
          delta_time.tv_usec = 1000000 + current_time.tv_usec - start_output_time.tv_usec;
        } else {
          delta_time.tv_sec = current_time.tv_sec - start_output_time.tv_sec;
          delta_time.tv_usec = current_time.tv_usec - start_output_time.tv_usec;
        }
        if (
          printf(
            "protocol=%hhu\n"
            "time.start=%li.%06li\n"
            "time.end=%li.%06li\n"
            "time.delta=%li.%06li\n"
            "iface.dropped=%"PRIi64"\n"
            "iface.errors=%"PRIi64"\n"
            "iface.fifo_errors=%"PRIi64"\n"
            "pcap.received=%"PRIi64"\n"
            "pcap.dropped=%"PRIi64"\n"
            "total.iface=%"PRIi64"/%"PRIi64"\n"
            "total.captured=%"PRIi64"/%"PRIi64"\n"
            "total.extrapolated=%"PRIi64"/%"PRIi64"\n"
            "unparseable=%"PRIi64"/%"PRIi64"\n"
            "other_network.icmp=%"PRIi64"/%"PRIi64"\n"
            "other_network.udp=%"PRIi64"/%"PRIi64"\n"
            "other_network.tcp=%"PRIi64"/%"PRIi64"\n"
            "other_network.other=%"PRIi64"/%"PRIi64"\n"
            "networks.length=%i\n",
            protocol_version,
            start_output_time.tv_sec, start_output_time.tv_usec,
            current_time.tv_sec,      current_time.tv_usec,
            delta_time.tv_sec,        delta_time.tv_usec,
            getSampleDelta(ifstats_dropped),
            getSampleDelta(ifstats_errors),
            getSampleDelta(ifstats_fifo_errors),
            getSampleDelta(stats_received),
            getSampleDelta(stats_dropped),
            getSampleDelta(ifstats_total.packets),                    getSampleDelta(ifstats_total.bytes),
            getSampleDelta(captured_counts.packets),                  getSampleDelta(captured_counts.bytes),
            getSampleDelta(total_counts.packets),                     getSampleDelta(total_counts.bytes),
            getSampleDelta(unparseable_counts.packets),               getSampleDelta(unparseable_counts.bytes),
            getSampleDelta(other_network_stats.icmp_counts.packets),  getSampleDelta(other_network_stats.icmp_counts.bytes),
            getSampleDelta(other_network_stats.udp_counts.packets),   getSampleDelta(other_network_stats.udp_counts.bytes),
            getSampleDelta(other_network_stats.tcp_counts.packets),   getSampleDelta(other_network_stats.tcp_counts.bytes),
            getSampleDelta(other_network_stats.other_counts.packets), getSampleDelta(other_network_stats.other_counts.bytes),
            num_networks
          )<0
        ) {
          retVal = errno;
          pcap_breakloop(descr);
          return;
        }
      } else {
        writeByte(protocol_version);
        writeHostLong(start_output_time.tv_sec);
        writeHostInt(start_output_time.tv_usec);
        writeHostLong(current_time.tv_sec);
        writeHostInt(current_time.tv_usec);
        writeSample(ifstats_dropped);
        writeSample(ifstats_errors);
        writeSample(ifstats_fifo_errors);
        writeSample(stats_received);
        writeSample(stats_dropped);
        writeCounts(ifstats_total);
        writeCounts(captured_counts);
        writeCounts(total_counts);
        writeCounts(unparseable_counts);
        writeProtocolCounts(&other_network_stats);
        writeHostInt(num_networks);
        if (retVal!=0) {
          pcap_breakloop(descr);
          return;
        }
      }
      copyEndToStartCounts(&ifstats_total);
      copyEndToStartSample(&ifstats_dropped);
      copyEndToStartSample(&ifstats_errors);
      copyEndToStartSample(&ifstats_fifo_errors);
      copyEndToStartSample(&stats_received);
      copyEndToStartSample(&stats_dropped);
      copyEndToStartCounts(&total_counts);
      copyEndToStartCounts(&unparseable_counts);
      copyEndToStartProtocolCounts(&other_network_stats);
#ifdef ASSERT
      // Assertions for total_counts = unparseable + no network + ip-specific
      struct counts total_check = unparseable_counts;
      addCheckProtocolCounts(&total_check, &other_network_stats);
#endif
      // Output per-network values
      int net_index;
      struct ipv4_network* networkIter = networks;
      for (net_index=0; net_index<num_networks; net_index++, networkIter++) {
        const int num_ips = 1 << (32 - networkIter->prefix);
        if (output == TEXT) {
          if (
            printf(
              "networks[%i].ip_version=4\n" // Only IPv4 currently supported
              "networks[%i].network=%s/%hhu\n"
              "networks[%i].total.icmp=%"PRIi64"/%"PRIi64"\n"
              "networks[%i].total.udp=%"PRIi64"/%"PRIi64"\n"
              "networks[%i].total.tcp=%"PRIi64"/%"PRIi64"\n"
              "networks[%i].total.other=%"PRIi64"/%"PRIi64"\n"
              "networks[%i].ips.length=%i\n",
              net_index,
              net_index, inet_ntoa(networkIter->network), networkIter->prefix,
              net_index, getSampleDelta(networkIter->total_counts.icmp_counts.packets),  getSampleDelta(networkIter->total_counts.icmp_counts.bytes),
              net_index, getSampleDelta(networkIter->total_counts.udp_counts.packets),   getSampleDelta(networkIter->total_counts.udp_counts.bytes),
              net_index, getSampleDelta(networkIter->total_counts.tcp_counts.packets),   getSampleDelta(networkIter->total_counts.tcp_counts.bytes),
              net_index, getSampleDelta(networkIter->total_counts.other_counts.packets), getSampleDelta(networkIter->total_counts.other_counts.bytes),
              net_index, num_ips
            )<0
          ) {
            retVal = errno;
            pcap_breakloop(descr);
            return;
          }
        } else {
          writeByte(4); // Only IPv4 currently supported
          writeNetInt(networkIter->network.s_addr);
          writeByte(networkIter->prefix);
          writeProtocolCounts(&networkIter->total_counts);
          if (retVal!=0) {
            pcap_breakloop(descr);
            return;
          }
        }
        copyEndToStartProtocolCounts(&networkIter->total_counts);
#ifdef ASSERT
        // Check total pet-IP matches total for network
        struct protocol_counts network_check = {0};
#endif
        // Output per-IP values
        int ip_index;
        struct protocol_counts* ipIter = networkIter->ips;
        for (ip_index=0; ip_index<num_ips; ip_index++, ipIter++) {
          struct in_addr hostaddress = networkIter->network;
          hostaddress.s_addr |= htonl(ip_index);
          if (output == TEXT) {
            if (
              printf(
                "networks[%i].ips[%i].host=%s\n"
                "networks[%i].ips[%i].icmp=%"PRIi64"/%"PRIi64"\n"
                "networks[%i].ips[%i].udp=%"PRIi64"/%"PRIi64"\n"
                "networks[%i].ips[%i].tcp=%"PRIi64"/%"PRIi64"\n"
                "networks[%i].ips[%i].other=%"PRIi64"/%"PRIi64"\n",
                net_index, ip_index, inet_ntoa(hostaddress),
                net_index, ip_index, getSampleDelta(ipIter->icmp_counts.packets),  getSampleDelta(ipIter->icmp_counts.bytes),
                net_index, ip_index, getSampleDelta(ipIter->udp_counts.packets),   getSampleDelta(ipIter->udp_counts.bytes),
                net_index, ip_index, getSampleDelta(ipIter->tcp_counts.packets),   getSampleDelta(ipIter->tcp_counts.bytes),
                net_index, ip_index, getSampleDelta(ipIter->other_counts.packets), getSampleDelta(ipIter->other_counts.bytes)
              )<0
            ) {
              retVal = errno;
              pcap_breakloop(descr);
              return;
            }
          } else {
            writeProtocolCounts(ipIter);
            if (retVal!=0) {
              pcap_breakloop(descr);
              return;
            }
          }
          copyEndToStartProtocolCounts(ipIter);
#ifdef ASSERT
          // Add for totals check
          addCheckNetworkCounts(&network_check, ipIter);
#endif
        }
#ifdef ASSERT
        // Assert network total = sum of ips
        if (memcmp(&network_check, &networkIter->total_counts, sizeof(struct protocol_counts)) != 0) {
          retVal = EPROTO;
          fprintf(
            stderr,
            "Assertion failed: network_check != network.total_counts:\n"
            /*"network_check.icmp.packets=%"PRIu64"\n"
            "network_check.icmp.bytes=%"PRIu64"\n"
            "network_check.udp.packets=%"PRIu64"\n"
            "network_check.udp.bytes=%"PRIu64"\n"
            "network_check.tcp.packets=%"PRIu64"\n"
            "network_check.tcp.bytes=%"PRIu64"\n"
            "network_check.other.packets=%"PRIu64"\n"
            "network_check.other.bytes=%"PRIu64"\n"
            "network.total_counts.icmp.packets=%"PRIu64"\n"
            "network.total_counts.icmp.bytes=%"PRIu64"\n"
            "network.total_counts.udp.packets=%"PRIu64"\n"
            "network.total_counts.udp.bytes=%"PRIu64"\n"
            "network.total_counts.tcp.packets=%"PRIu64"\n"
            "network.total_counts.tcp.bytes=%"PRIu64"\n"
            "network.total_counts.other.packets=%"PRIu64"\n"
            "network.total_counts.other.bytes=%"PRIu64"\n",
            network_check.icmp_counts.packets,
            network_check.icmp_counts.bytes,
            network_check.udp_counts.packets,
            network_check.udp_counts.bytes,
            network_check.tcp_counts.packets,
            network_check.tcp_counts.bytes,
            network_check.other_counts.packets,
            network_check.other_counts.bytes,
            networkIter->total_counts.icmp_counts.packets,
            networkIter->total_counts.icmp_counts.bytes,
            networkIter->total_counts.udp_counts.packets,
            networkIter->total_counts.udp_counts.bytes,
            networkIter->total_counts.tcp_counts.packets,
            networkIter->total_counts.tcp_counts.bytes,
            networkIter->total_counts.other_counts.packets,
            networkIter->total_counts.other_counts.bytes*/
          );
          pcap_breakloop(descr);
          return;
        }
        // Add network to totals check
        addCheckProtocolCounts(&total_check, &network_check);
#endif
      }
#ifdef ASSERT
      // Assertions for total_counts = unparseable + no network + ip-specific
      if (memcmp(&total_check, &total_counts, sizeof(struct counts)) != 0) {
        retVal = EPROTO;
        fprintf(
          stderr,
          "Assertion failed: total_check != total_counts:\n"
          /*"total_check.packets=%"PRIu64"\n"
          "total_check.bytes=%"PRIu64"\n"
          "total_counts.packets=%"PRIu64"\n"
          "total_counts.bytes=%"PRIu64"\n",
          total_check.packets,
          total_check.bytes,
          total_counts.packets,
          total_counts.bytes*/
        );
        pcap_breakloop(descr);
        return;
      }
#endif
      fflush(stdout);
    } else {
      fprintf(stderr, "Unexpected protocol version: %hhu\n", protocol_version);
      retVal = EPROTONOSUPPORT;
      pcap_breakloop(descr);
    }
  }
}

int main(int argc, char *argv[] ) {
  if (argc < 6) {
    fprintf(stderr, "Usage: %s protocol_version text|binary iface_name in|out src|dst network/prefix [network/prefix [...]]\n", argv[0]);
    retVal = EINVAL;
  } else {
    // Parse command line parameters and allocate data structures
    if (strcmp(argv[1], "1")==0) {
      protocol_version = 1;
    } else {
      fprintf(stderr, "Unsupported protocol version: must be \"1\": %s\n", argv[1]);
      retVal = EINVAL;
    }
    if (retVal == 0) {
      if (strcmp(argv[2], "text")==0) {
        output = TEXT;
      } else if (strcmp(argv[2], "binary")==0) {
        output = BINARY;
      } else {
        fprintf(stderr, "Invalid output type, must be either \"text\" or \"binary\": %s\n", argv[2]);
        retVal = EINVAL;
      }
      if (retVal == 0) {
        if (strcmp(argv[4], "in")==0) {
          network_direction = PCAP_D_IN;
        } else if (strcmp(argv[4], "out")==0) {
          network_direction = PCAP_D_OUT;
        } else {
          fprintf(stderr, "Invalid network direction, must be either \"in\" or \"out\": %s\n", argv[4]);
          retVal = EINVAL;
        }
        if (retVal == 0) {
          if (strcmp(argv[5], "src")==0) {
            count_direction = SOURCE;
          } else if (strcmp(argv[5], "dst")==0) {
            count_direction = DESTINATION;
          } else {
            fprintf(stderr, "Invalid count direction, must be either \"src\" or \"dst\": %s\n", argv[5]);
            retVal = EINVAL;
          }
          if (retVal == 0) {
            device = argv[3];
            num_networks = argc - 6;
            networks = (struct ipv4_network*)calloc(num_networks, sizeof(struct ipv4_network));
            if (networks == NULL) {
              printErrno("calloc", errno);
              retVal = errno==0 ? ENOMEM : errno;
            } else {
              int i;
              for (i=6; i<argc; i++) {
                char* network = argv[i];
#ifdef DEBUG
                fprintf(stderr, "Parsing %s\n", network);
#endif
                retVal = parse_ipv4_network(&networks[i-6], network);
                if (retVal != 0) {
                  break;
                }
              }
              if (retVal == 0) {
                // Fetch starting time
                if (gettimeofday(&last_output_time, NULL) != 0) {
                  printErrno("gettimeofday", errno);
                  retVal = errno;
                } else {
                  // Fetch starting iface_stats
                  if (readInterfaceStats(device, network_direction, &ifstats_total, &ifstats_dropped, &ifstats_errors, &ifstats_fifo_errors) != 0) {
                    retVal = errno;
                  } else {
                    copyEndToStartCounts(&ifstats_total);
                    ifstats_start_packets = ifstats_total.packets.start;
                    ifstats_start_bytes   = ifstats_total.bytes.start;
                    copyEndToStartSample(&ifstats_dropped);
                    copyEndToStartSample(&ifstats_errors);
                    copyEndToStartSample(&ifstats_fifo_errors);
#ifdef DEBUG
                    fprintf(stderr, "ifstats.total.packets.start=%"PRIu64"\n", ifstats_total.packets.start);
                    fprintf(stderr, "ifstats.total.bytes.start=%"PRIu64"\n", ifstats_total.bytes.start);
#endif
                    // Reserve space of pcap errors
                    char errbuf[PCAP_ERRBUF_SIZE];

                    // Open device
#ifdef DEBUG
                    fprintf(stderr, "Opening device %s\n", device);
#endif
                    errbuf[0] = '\0';
                    descr = pcap_open_live(device, CAPTURE_BYTES, TRUE, READ_TIMEOUT, errbuf);
                    if (descr == NULL) {
                      printError("pcap_open_live", errbuf);
                      retVal = errno==0 ? EIO : errno;
                    } else {
                      printWarning("pcap_open_live", errbuf);

                      // Set direction
#ifdef DEBUG
                      fprintf(stderr, "Setting network direction\n");
#endif
                      if (pcap_setdirection(descr, network_direction) != 0) {
                        printErrno("pcap_loop", errno);
                        retVal = errno==0 ? EIO : errno;
                      } else {
                        // Check link layer type
                        int linkType = pcap_datalink(descr);
#ifdef DEBUG
                        fprintf(stderr, "Link Type: %s\n", pcap_datalink_val_to_name(linkType));
#endif
                        if (linkType != DLT_EN10MB) {
                          fprintf(stderr, "Only Ethernet supported\n");
                          retVal = EPROTONOSUPPORT;
                        } else {
                          // Loops until any error occurs
                          if (pcap_loop(descr, -1, processPacket, NULL) == -1) {
                            printErrno("pcap_loop", errno);
                            retVal = errno==0 ? EIO : errno;
                          }
                        }
                      }

                      // Close device
#ifdef DEBUG
                      fprintf(stderr, "Closing device %s\n", device);
#endif
                      pcap_close(descr);
                    }
                  }
                }
              }

              // Free memory
              for (i=0; i<num_networks; i++) free(networks[i].ips);
              free(networks);
            }
          }
        }
      }
    }
  }
  return retVal;
}
