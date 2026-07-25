/*
 * ndpi_api.h
 *
 * Copyright (C) 2011-25 - ntop.org
 *
 * This file is part of nDPI, an open source deep packet inspection
 * library based on the OpenDPI and PACE technology by ipoque GmbH
 *
 * nDPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * nDPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with nDPI.  If not, see <http://www.gnu.org/licenses/>.
 *
 */


#ifndef __NDPI_API_H__
#define __NDPI_API_H__

#include "ndpi_main.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SIZEOF_FLOW_STRUCT                    ( sizeof(struct ndpi_flow_struct) )

#define NDPI_DETECTION_ONLY_IPV4              ( 1 << 0 )
#define NDPI_DETECTION_ONLY_IPV6              ( 1 << 1 )

  /*
    In case a custom DGA function is used, the function
    below must be overwritten,
  */
  extern ndpi_custom_dga_predict_fctn ndpi_dga_function;

  /**
   * Check if a string is encoded with punycode
   * ( https://tools.ietf.org/html/rfc3492 )
   *
   * @par    buff = pointer to the string to check
   * @par    len  = len of the string
   * @return 1 if the string is punycoded;
   *         else 0
   *
   */
  int ndpi_check_punycode_string(char *buff, int len);


  /**
   * Get the size of the flow struct
   *
   * @return the size of the flow struct
   *
   */
  u_int32_t ndpi_detection_get_sizeof_ndpi_flow_struct(void);

  /**
   * Match a string against an automaton and retrieve its associated numeric value.
   * This function is similar to ndpi_match_string_subprotocol() but is used for
   * matching raw IDs that were added via ndpi_add_string_value_to_automa().
   *
   * @param _automa Automaton created with ndpi_init_automa()
   * @param string_to_match String to search for
   * @param match_len Length of the string to match
   * @param num Pointer to store the associated numeric value (output parameter)
   * @return 1 if a match was found, 0 otherwise
   */
  int ndpi_match_string_value(void *_automa, char *string_to_match,
			      u_int match_len, u_int32_t *num);

  /**
   * Return the protocol error code of a given flow
   *
   * @par    flow    = the flow to analyze
   * @return the error code or 0 otherwise
   *
   */
  u_int32_t ndpi_get_flow_error_code(struct ndpi_flow_struct *flow);

  /**
   * Allocate memory using nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom allocator.
   *
   * @param size Number of bytes to allocate
   * @return Pointer to allocated memory, or NULL on failure
   */
  void * ndpi_malloc(size_t size);

  /**
   * Allocate and zero-initialize memory using nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom allocator.
   *
   * @param count Number of elements to allocate
   * @param size Size of each element in bytes
   * @return Pointer to zero-initialized memory, or NULL on failure
   */
  void * ndpi_calloc(size_t nmemb, size_t size);

  /**
   * Reallocate memory using nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom allocator.
   *
   * @param ptr Pointer to previously allocated memory (or NULL for new allocation)
   * @param old_size Current size of the allocated block in bytes
   * @param new_size Desired new size in bytes
   * @return Pointer to reallocated memory, or NULL on failure
   */
  void * ndpi_realloc(void *ptr, size_t size);

  /**
   * Allocate aligned memory using nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom allocator.
   *
   * @param the address of the allocated memory will be a multiple of `alignment`
   * @param size Number of bytes to allocate
   * @return Pointer to allocated memory, or NULL on failure
   */
  void * ndpi_aligned_malloc(size_t alignment, size_t size);

  /**
   * Duplicate a string using nDPI's memory allocator.
   * The returned string must be freed with ndpi_free().
   *
   * @param s String to duplicate (null-terminated)
   * @return Pointer to newly allocated string copy, or NULL on failure
   */
  char * ndpi_strdup(const char *s);

  /**
   * Duplicate a string with length limit using nDPI's memory allocator.
   * The returned string must be freed with ndpi_free().
   *
   * @param s String to duplicate
   * @param size Maximum number of characters to copy (excluding null terminator)
   * @return Pointer to newly allocated string copy, or NULL on failure
   */
  char * ndpi_strndup(const char *s, size_t size);

  /**
   * Free memory allocated by nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom deallocator.
   *
   * @param ptr Pointer to memory to free (NULL is safe to pass)
   */
  void   ndpi_free(void *ptr);

  /**
   * Free aligned memory allocated by nDPI's memory allocator.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom deallocator.
   *
   * @param ptr Pointer to memory to free (NULL is safe to pass)
   */
  void   ndpi_aligned_free(void *ptr);

  /**
   * Allocate memory for flow-specific data using nDPI's flow allocator.
   * Flow memory can use a separate allocator from general memory for better
   * memory management in high-throughput scenarios.
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom allocator.
   *
   * @param size Number of bytes to allocate
   * @return Pointer to allocated memory, or NULL on failure
   */
  void * ndpi_flow_malloc(size_t size);

  /**
   * Free memory allocated by ndpi_flow_malloc().
   * This function can be customized via ndpi_set_memory_alloction_functions() to use a custom deallocator.
   *
   * @param ptr Pointer to flow memory to free (NULL is safe to pass)
   */
  void   ndpi_flow_free(void *ptr);

  /**
   * Get the total amount of memory allocated by nDPI.
   * This tracks memory allocated via ndpi_malloc(), ndpi_calloc(), ndpi_realloc(),
   * ndpi_strdup(), and ndpi_strndup() (but not flow allocations).
   *
   * @return Total number of bytes currently allocated
   */
  u_int32_t ndpi_get_tot_allocated_memory(void);

  /**
   * Remove leading and trailing whitespace from a string in-place.
   *
   * @param ptr Pointer to the string to process (modified in-place)
   * @param ptr_len Pointer to the string length (updated to new length after stripping)
   * @return Pointer to the start of the trimmed string (within the original buffer)
   */
  char *ndpi_strip_leading_trailing_spaces(char *ptr, int *ptr_len) ;

  /**
   * Finds the first occurrence of the substring 'needle' in the string 'haystack'.
   *
   * This function is similar to the standard `strstr()` function, but it has an additional parameter `len` that
   * specifies the maximum length of the search.
   *
   * @param haystack The string to search in.
   * @param needle The substring to search for.
   * @param len The maximum length of the search.
   * @return Pointer to the first occurrence of 'needle' in 'haystack', or NULL if no match is found.
   */
  char *ndpi_strnstr(const char *haystack, const char *needle, size_t len);

  /**
   * Same as ndpi_strnstr but case insensitive
   *
   * @par    s     = string to parse
   * @par    find  = string to match with -s-
   * @par    slen  = max length to match between -s- and -find-
   * @return a pointer to the beginning of the located substring;
   *         NULL if the substring is not found
   *
   */
  const char* ndpi_strncasestr(const char *s, const char *find, size_t slen);

  /**
   * Returns the nDPI protocol id for IP-based protocol detection
   *
   * @par    ndpi_struct  = the struct created for the protocol detection
   * @par    pin          = IP host address (MUST BE in network byte order):
   *                        See man(7) ip for details
   * @return the nDPI protocol ID
   *
   */
  u_int16_t ndpi_network_ptree_match(struct ndpi_detection_module_struct *ndpi_struct,
				     struct in_addr *pin);
  u_int16_t ndpi_network_ptree6_match(struct ndpi_detection_module_struct *ndpi_str,
				      struct in6_addr *pin);

  /**
   * Returns the nDPI protocol id for IP+port-based protocol detection
   *
   * @par    ndpi_struct  = the struct created for the protocol detection
   * @par    pin          = IP host address (MUST BE in network byte order):
   *                        See man(7) ip for details
   * @par    port         = The port (MUST BE in network byte order) or
   *                        0 if ignored
   * @return the nDPI protocol ID
   *
   */
  u_int16_t ndpi_network_port_ptree_match(struct ndpi_detection_module_struct *ndpi_struct,
					  struct in_addr *pin /* network byte order */,
					  u_int16_t port /* network byte order */);
  u_int16_t ndpi_network_port_ptree6_match(struct ndpi_detection_module_struct *ndpi_struct,
					   struct in6_addr *pin,
					   u_int16_t port /* network byte order */);

  /**
   * Returns a new initialized global context.
   *
   * @return  the initialized global context
   *
   */
  struct ndpi_global_context *ndpi_global_init(void);

  /**
   * Deinit a properly initialized global context.
   *
   * @par g_ctx = global context to free/deinit
   *
   */
  void ndpi_global_deinit(struct ndpi_global_context *g_ctx);

  /**
   * Returns a new initialized detection module
   * Note that before you can use it you can still load
   * hosts and do other things. As soon as you are ready to use
   * it do not forget to call first ndpi_finalize_initialization()
   *
   * You can call this function multiple times, (i.e. to create multiple
   * independent detection contexts) but all these calls MUST NOT run
   * in parallel
   *
   * @par g_ctx = global context associated to the new detection module; NULL if no global context is needed
   * @return  the initialized detection module
   *
   */
  struct ndpi_detection_module_struct *ndpi_init_detection_module(struct ndpi_global_context *g_ctx);

  /**
   * Completes the initialization (2nd step)
   *
   * @par ndpi_str = the struct created for the protocol detection
   *
   * @return 0 on success
   *
   */
  int ndpi_finalize_initialization(struct ndpi_detection_module_struct *ndpi_str);

  /**
   * Frees the dynamic memory allocated members in the specified flow
   *
   * @par flow  = the flow struct which dynamic allocated members should be deallocated
   *
   */
  void ndpi_free_flow_data(struct ndpi_flow_struct *flow);

  /**
   * Frees the dynamic memory allocated members in the specified flow and the flow struct itself
   *
   * @par flow  = the flow struct and its dynamic allocated members that should be deallocated
   *
   */
  void ndpi_free_flow(struct ndpi_flow_struct *flow);

  /**
   * Destroys the detection module
   *
   * @par ndpi_struct  = the struct to clearing for the detection module
   *
   */
  void ndpi_exit_detection_module(struct ndpi_detection_module_struct *ndpi_struct);

  /**
   *  Function to be called before we give up with detection for a given flow.
   *  This function reduces the NDPI_UNKNOWN_PROTOCOL detection
   *
   * @par    ndpi_struct  = the detection module
   * @par    flow         = the flow given for the detection module
   * @return the detected protocol even if the flow is not completed;
   *
   */
  ndpi_protocol ndpi_detection_giveup(struct ndpi_detection_module_struct *ndpi_struct,
				      struct ndpi_flow_struct *flow);

  /**
   * Processes one packet and returns the ID of the detected protocol.
   * This is the MAIN PACKET PROCESSING FUNCTION.
   *
   * @par    ndpi_struct    = the detection module
   * @par    flow           = pointer to the connection state machine
   * @par    packet         = unsigned char pointer to the Layer 3 (IP header)
   * @par    packetlen      = the length of the packet
   * @par    packet_time_ms = the current timestamp for the packet (expressed in msec)
   * @par    input_info     = (optional) flow information provided by the (external) flow manager
   * @return the detected ID of the protocol
   *
   */
  ndpi_protocol ndpi_detection_process_packet(struct ndpi_detection_module_struct *ndpi_struct,
					      struct ndpi_flow_struct *flow,
					      const unsigned char *packet,
					      const unsigned short packetlen,
					      const u_int64_t packet_time_ms,
					      struct ndpi_flow_input_info *input_info);
  /**
   * Get the main protocol of the passed flows for the detected module
   *
   *
   * @par    flow         = the flow given for the detection module
   * @return the ID of the master protocol detected
   *
   */
  u_int16_t ndpi_get_flow_masterprotocol(struct ndpi_flow_struct *flow);

  /**
   * Get the app protocol of the passed flows for the detected module
   *
   *
   * @par    flow         = the flow given for the detection module
   * @return the ID of the app protocol detected
   *
   */
  u_int16_t ndpi_get_flow_appprotocol(struct ndpi_flow_struct *flow);

  /**
   * Get the category of the passed flows for the detected module
   *
   *
   * @par    flow         = the flow given for the detection module
   * @return the ID of the category
   *
   */
  ndpi_protocol_category_t ndpi_get_flow_category(struct ndpi_flow_struct *flow);

  /**
   * Get the ndpi protocol data of the passed flows for the detected module
   *
   *
   * @par    flow         = the flow given for the detection module
   * @par    ndpi_proto   = the output struct where to store the requested information
   *
   */
  void ndpi_get_flow_ndpi_proto(struct ndpi_flow_struct *flow,
				struct ndpi_proto * ndpi_proto);

  /**
   * Query the pointer to the layer 4 packet
   *
   * @par    l3 = pointer to the layer 3 data
   * @par    l3_len = length of the layer 3 data
   * @par    l4_return = address to the pointer of the layer 4 data if return value == 0, else undefined
   * @par    l4_len_return = length of the layer 4 data if return value == 0, else undefined
   * @par    l4_protocol_return = protocol of the layer 4 data if return value == 0, undefined otherwise
   * @par    flags = limit operation on ipv4 or ipv6 packets. Possible values: NDPI_DETECTION_ONLY_IPV4 - NDPI_DETECTION_ONLY_IPV6 - 0 (any)
   * @return 0 if layer 4 data could be found correctly;
   else != 0
   *
   */
  u_int8_t ndpi_detection_get_l4(const u_int8_t *l3, u_int16_t l3_len,
				 const u_int8_t **l4_return, u_int16_t *l4_len_return,
				 u_int8_t *l4_protocol_return, u_int32_t flags);

  /**
   * Search and return the protocol based on matched ports
   *
   * @par    ndpi_struct  = the detection module
   * @par    shost        = source address in host byte order
   * @par    sport        = source port number
   * @par    dhost        = destination address in host byte order
   * @par    dport        = destination port number
   * @return the struct ndpi_protocol that match the port base protocol
   *
   */
  ndpi_protocol ndpi_find_port_based_protocol(struct ndpi_detection_module_struct *ndpi_struct/* , u_int8_t proto */,
					      u_int32_t shost, u_int16_t sport,
					      u_int32_t dhost, u_int16_t dport);
  /**
   * Search and return the protocol guessed that is undetected
   *
   * @par    ndpi_struct  = the detection module
   * @par    flow         = the flow we're trying to guess, NULL if not available
   * @par    proto        = the l4 protocol number
   * @return the struct ndpi_protocol that match the port base protocol
   *
   */
  ndpi_protocol ndpi_guess_undetected_protocol(struct ndpi_detection_module_struct *ndpi_struct,
					       struct ndpi_flow_struct *flow,
					       u_int8_t proto);

  /**
   * Superset of ndpi_guess_undetected_protocol with additional IPv4 guess based on host/port
   *
   * @par    ndpi_struct  = the detection module
   * @par    flow         = the flow we're trying to guess, NULL if not available
   * @par    proto        = the l4 protocol number
   * @par    shost        = source address in host byte order
   * @par    sport        = source port number
   * @par    dhost        = destination address in host byte order
   * @par    dport        = destination port number
   * @return the struct ndpi_protocol that match the port base protocol
   *
   */
  ndpi_protocol ndpi_guess_undetected_protocol_v4(struct ndpi_detection_module_struct *ndpi_struct,
						  struct ndpi_flow_struct *flow,
						  u_int8_t proto,
						  u_int32_t shost, u_int16_t sport,
						  u_int32_t dhost, u_int16_t dport);
  /**
   * Check if the string passed match with a protocol
   *
   * @par    ndpi_struct         = the detection module
   * @par    string_to_match     = the string to match
   * @par    string_to_match_len = the length of the string
   * @par    ret_match           = completed returned match information
   * @return the ID of the matched subprotocol;
   *         -1 if automa is not finalized;
   *         -2 if automa==NULL or string_to_match==NULL or empty string_to_match
   *
   */
  int ndpi_match_string_subprotocol(struct ndpi_detection_module_struct *ndpi_struct,
				    char *string_to_match,
				    u_int string_to_match_len,
				    ndpi_protocol_match_result *ret_match);
  /**
   * Check if the host passed match with a protocol
   *
   * @par    ndpi_struct         = the detection module
   * @par    flow                = the flow where match the host
   * @par    string_to_match     = the string to match
   * @par    string_to_match_len = the length of the string
   * @par    ret_match           = completed returned match information
   * @par    master_protocol_id  = value of the ID associated to the master protocol detected
   * @par    update_flow_classification = update or not protocol (sub)classification
   * @return the ID of the matched subprotocol
   *
   */
  u_int16_t ndpi_match_host_subprotocol(struct ndpi_detection_module_struct *ndpi_struct,
					struct ndpi_flow_struct *flow,
					char *string_to_match,
					u_int string_to_match_len,
					ndpi_protocol_match_result *ret_match,
					u_int16_t master_protocol_id,
					int update_flow_classification);

  /**
   * Check if the string content passed match with a protocol
   *
   * @par    ndpi_struct         = the detection module
   * @par    flow                = the flow where match the host
   * @par    subprotocol_id      = subprotocol id
   */
  void ndpi_check_subprotocol_risk(struct ndpi_detection_module_struct *ndpi_str,
				 struct ndpi_flow_struct *flow, u_int16_t subprotocol_id);

  /**
   * Check if the string -bigram_to_match- match with a bigram of -automa-
   *
   * @par     ndpi_mod         = the detection module
   * @par     automa           = the struct ndpi_automa for the bigram
   * @par     bigram_to_match  = the bigram string to match
   * @return  0
   *
   */
  int ndpi_match_bigram(const char *bigram_to_match);

  /**
   * Write the protocol name in the buffer -buf- as master_protocol.protocol
   *
   * @par     ndpi_mod      = the detection module
   * @par     proto         = the struct ndpi_master_app_protocol contain the protocols name
   * @par     buf           = the buffer to write the name of the protocols
   * @par     buf_len       = the length of the buffer
   * @return  the buffer contains the master_protocol and protocol name
   *
   */
  char* ndpi_protocol2name(struct ndpi_detection_module_struct *ndpi_mod,
                           ndpi_master_app_protocol proto, char *buf, u_int buf_len);

  /**
   * Same as ndpi_protocol2name() with the difference that the numeric protocol
   * name is returned
   *
   * @par     proto         = the struct ndpi_master_app_protocol contain the protocols name
   * @par     buf           = the buffer to write the name of the protocols
   * @par     buf_len       = the length of the buffer
   * @return  the buffer contains the master_protocol and protocol name
   *
   */
  char* ndpi_protocol2id(ndpi_master_app_protocol proto, char *buf, u_int buf_len);

  /**
   * Find out if a given category is custom/user-defined
   *
   * @par     category      = the category associated to the protocol
   * @return  True if this is a custom user category, false otherwise
   *
   */
  bool ndpi_is_custom_category(ndpi_protocol_category_t category);

  /**
   * Find out if a given protocol is custom/user-defined
   *
   * @par     ndpi_str      = the detection module
   * @par     proto_id      = the proto_id to check
   * @return  True if this is a custom user protocol, false otherwise (nDPI protocol already supported in the engine)
   *
   */
  bool ndpi_is_custom_protocol(struct ndpi_detection_module_struct *ndpi_str, u_int16_t proto_id);

  /**
   * Overwrite a protocol category defined by nDPI with the custom category
   *
   * @par     ndpi_mod      = the detection module
   * @par     protoId       = the protocol identifier to overwrite
   * @par     breed         = the breed to be associated to the protocol
   *
   */
  void ndpi_set_proto_breed(struct ndpi_detection_module_struct *ndpi_mod,
			    u_int16_t protoId, ndpi_protocol_breed_t breed);

  /**
   * Overwrite a protocol category defined by nDPI with the custom category
   *
   * @par     ndpi_mod      = the detection module
   * @par     protoId       = the protocol identifier to overwrite
   * @par     category      = the category associated to the protocol
   *
   */
  void ndpi_set_proto_category(struct ndpi_detection_module_struct *ndpi_mod,
			       u_int16_t protoId, ndpi_protocol_category_t protoCategory);

  /**
   * Find the QoE category for the specified protocol
   *
   * @par     ndpi_mod      = the detection module
   * @par     protoId       = the protocol identifier we're searching
   *
   */
  ndpi_protocol_qoe_category_t ndpi_find_protocol_qoe(struct ndpi_detection_module_struct *ndpi_str,
						      u_int16_t protoId);

  /**
   * Return the name of a RTP payload type
   *
   * @par     payload_type     = the RTP payload type
   * @par     evs_payload_type = EVS payload type (only in case payload_type is EVS)
   * @return  The symbolic payload type or "Unknown" if not found
   */
  const char* ndpi_rtp_payload_type2str(u_int8_t payload_type, u_int32_t evs_payload_type);

  /**
   * Check if subprotocols of the specified master protocol are just
   * informative (and not real)
   *
   * @par ndpi_mod          = the detection module
   * @par     protoId       = the (master) protocol identifier to query
   * @return  1 = the subprotocol is informative, 0 otherwise.
   *
   */
  u_int8_t ndpi_is_subprotocol_informative(struct ndpi_detection_module_struct *ndpi_mod, u_int16_t protoId);

  /**
   * Set hostname-based protocol
   *
   * @par ndpi_mod          = the detection module
   * @par flow              = the flow to which this communication belongs to
   * @par master_protocol   = the master protocol for this flow
   * @par name              = the host name
   * @par name_len          = length of the host name
   *
   */
  int ndpi_match_hostname_protocol(struct ndpi_detection_module_struct *ndpi_mod,
				   struct ndpi_flow_struct *flow,
				   u_int16_t master_protocol,
				   char *name, u_int name_len);

  /**
   * Get protocol category as string
   *
   * @par     mod           = the detection module
   * @par     category      = the category associated to the protocol
   * @return  the string name of the category
   *
   */
  const char* ndpi_category_get_name(struct ndpi_detection_module_struct *ndpi_mod,
				     ndpi_protocol_category_t category);

  /**
   * Get classification confidence as string
   *
   * @par     confidence      = the confidence value
   * @return  the string name of the confidence result
   *
   */
  const char* ndpi_confidence_get_name(ndpi_confidence_t confidence);

  /**
   * Get FPC confidence as string
   *
   * @par     confidence      = the confidence value
   * @return  the string name of the confidence result
   *
   */
  const char* ndpi_fpc_confidence_get_name(ndpi_fpc_confidence_t fpc_confidence);

  /**
   * Set protocol category string
   *
   * @par     mod           = the detection module
   * @par     category      = the category associated to the protocol
   * @par     name          = the string name of the category
   *
   */
  void ndpi_category_set_name(struct ndpi_detection_module_struct *ndpi_mod,
			      ndpi_protocol_category_t category, char *name);

  /**
   * Get protocol category
   *
   * @par     ndpi_mod      = the detection module
   * @par     proto         = the struct ndpi_protocol contain the protocols name
   * @return  the protocol category
   */
  ndpi_protocol_category_t ndpi_get_proto_category(struct ndpi_detection_module_struct *ndpi_mod,
						   ndpi_protocol proto);

  /**
   * Get the protocol name associated to the ID
   *
   * @par     mod           = the detection module
   * @par     proto_id      = the ID of the protocol
   * @return  the buffer contains the master_protocol and protocol name
   *
   */
  char* ndpi_get_proto_name(struct ndpi_detection_module_struct *mod, u_int16_t proto_id);


  /**
   * Return the protocol breed ID associated to the protocol
   *
   * @par     ndpi_struct   = the detection module
   * @par     proto         = the ID of the protocol
   * @return  the breed ID associated to the protocol
   *
   */
  ndpi_protocol_breed_t ndpi_get_proto_breed(struct ndpi_detection_module_struct *ndpi_struct,
					     u_int16_t proto);

  /**
   * Get the protocol breed ID associated to the breed name
   *
   * @par     name          = the string name of the breed
   * @return  the breed ID associated to the name, or NDPI_PROTOCOL_UNRATED if not found
   *
   */
  ndpi_protocol_breed_t ndpi_get_breed_by_name(const char *name);

  /**
   * Return the string name of the protocol breed
   *
   * @par     ndpi_struct   = the detection module
   * @par     breed_id      = the breed ID associated to the protocol
   * @return  the string name of the breed ID
   *
   */
  char* ndpi_get_proto_breed_name(ndpi_protocol_breed_t breed_id);

  /**
   * Return the name of the protocol given its ID.
   *
   * @par     ndpi_mod   = the detection module
   * @par     name       = the protocol name. You can specify TLS or YouYube but not TLS.YouTube (se ndpi_get_protocol_by_name in this case)
   * @return  the ID of the protocol
   *
   */
  extern u_int16_t ndpi_get_proto_by_name(const struct ndpi_detection_module_struct *ndpi_mod, const char *name);

  /**
   * Return the name of the protocol given its ID
   *
   * @par     ndpi_mod   = the detection module
   * @par     id         = the protocol id
   * @return  the name of the protocol
   *
   */
  extern char* ndpi_get_proto_by_id(const struct ndpi_detection_module_struct *ndpi_mod, u_int id);

  /**
   * Return the name of the protocol given its ID. You can specify TLS.YouTube or just TLS
   *
   * @par     ndpi_mod   = the detection module
   * @par     id         = the protocol id
   * @return  the name of the protocol
   *
   */
  extern ndpi_master_app_protocol ndpi_get_protocol_by_name(struct ndpi_detection_module_struct *ndpi_str, const char *name);

  /**
   * Return the ID of the category
   *
   * @par     ndpi_mod   = the detection module
   * @par     proto      = the category name
   * @return  the ID of the category
   *
   */
  int ndpi_get_category_id(struct ndpi_detection_module_struct *ndpi_mod, char *cat);

  /**
   * Write the list of the supported protocols
   *
   * @par  ndpi_mod = the detection module
   */
  void ndpi_dump_protocols(struct ndpi_detection_module_struct *mod, FILE *dump_out);

  /**
   * Generate Options list used in OPNsense firewall plugin
   *
   * @par  opt = The Option list to generate
   * @par  dump_out = Output stream for generated options
   */
  void ndpi_generate_options(u_int opt, FILE *dump_out);

  /**
   * Write the list of the scores and their associated risks
   *
   * @par  dump_out = Output stream for dumped risk scores
   */
  void ndpi_dump_risks_score(FILE *dump_out);

  /**
   * Read a file and load the protocols
   *
   * Format: <tcp|udp>:<port>,<tcp|udp>:<port>,.....@<proto>
   *
   * Example:
   * tcp:80,tcp:3128@HTTP
   * udp:139@NETBIOS
   *
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 generic error
   *          -2 memory allocation error
   *
   */
  int ndpi_load_protocols_file(struct ndpi_detection_module_struct *ndpi_mod,
			       const char* path);

  /**
   * Add an IP-address based risk mask
   *
   * @par     ndpi_mod = the detection module
   * @par     ip       = the IP address for which you wanna set the mask
   * @par     mask     = the IP risk mask
   * @return  0 if the rule is loaded correctly;
   *          -1 else
   */
  int ndpi_add_ip_risk_mask(struct ndpi_detection_module_struct *ndpi_mod, char *ip, ndpi_risk mask);

  /**
   * Add a host-address based risk mask
   *
   * @par     ndpi_mod = the detection module
   * @par     host     = the hostname/domain for which you wanna set the mask
   * @par     mask     = the host risk mask
   * @return  0 if the rule is loaded correctly;
   *          -1 else
   */
  int ndpi_add_host_risk_mask(struct ndpi_detection_module_struct *ndpi_mod, char *host, ndpi_risk mask);

  /**
   * Add a trusted certificate issuer DN
   *
   * @par     ndpi_mod = the detection module
   * @par     dn       = the issuer DN as it appears in the certificate (example "CN=813845657003339838, O=Code42, OU=TEST, ST=MN, C=US")
   * @return  0 if the rule is loaded correctly; < 0 in case an error is detected
   */
  int ndpi_add_trusted_issuer_dn(struct ndpi_detection_module_struct *ndpi_mod, char *dn);

  /**
   * Read a file and load the categories
   *
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @par     user_data = pointer to some user data value
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_categories_file(struct ndpi_detection_module_struct *ndpi_str, const char* path, void *user_data);

  /**
   * Loads a file (separated by <cr>) of domain names associated with the specified category
   *
   * @par     ndpi_mod    = the detection module
   * @par     path        = the path of the file
   * @par     category_id = Id of the category to which domains will be associated
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_category_file(struct ndpi_detection_module_struct *ndpi_str,
			      char* path, ndpi_protocol_category_t category_id);

  /**
   * Load files (whose name is <categoryid>_<label>.<extension>) stored
   * in a directory and bind each domain to the specified category.
   *
   * @par     ndpi_mod    = the detection module
   * @par     path        = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_categories_dir(struct ndpi_detection_module_struct *ndpi_str,
			       char* path);

  /**
   * Load files (whose name is <protocolid>_<label>.<extension>) stored
   * in a directory and binds each IP/network to the specified protocol.
   * This function is used to bind IP addresses to protocols
   *
   * @par     ndpi_mod    = the detection module
   * @par     path        = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_protocols_dir(struct ndpi_detection_module_struct *ndpi_str,
			       char* path);

  /**
   * Read a file and load the list of risky domains
   *
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_risk_domain_file(struct ndpi_detection_module_struct *ndpi_str, const char* path);

  /**
   * Read a file and load the list of malicious JA4 signatures
   *
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_malicious_ja4_file(struct ndpi_detection_module_struct *ndpi_str, const char *path);

  /**
   * Read a file and load the list of malicious SSL certificate SHA1 fingerprints.
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int ndpi_load_malicious_sha1_file(struct ndpi_detection_module_struct *ndpi_str, const char *path);

  /*
    Add a new TCP fingerprint

    Return code:
    0   OK
    -1  Duplicated fingerprint
    -2  Unable to add a new entry
  */
  int ndpi_add_tcp_fingerprint(struct ndpi_detection_module_struct *ndpi_str,
			       char *fingerprint, ndpi_os os);

  /**
   * Read a file and load the list of TCP fingerprints
   * @par     ndpi_mod = the detection module
   * @par     path     = the path of the file
   * @return  0 if the file is loaded correctly;
   *          -1 else
   */
  int load_tcp_fingerprint_file_fd(struct ndpi_detection_module_struct *ndpi_str, FILE *fd);
  int ndpi_load_tcp_fingerprint_file(struct ndpi_detection_module_struct *ndpi_str, const char *path);
  void ndpi_load_tcp_fingerprints(struct ndpi_detection_module_struct *ndpi_str);
  ndpi_os ndpi_get_os_from_tcp_fingerprint(struct ndpi_detection_module_struct *ndpi_str,
					   char *tcp_fingerprint);

  /**
   * Get the total number of the defined protocols (internals and custom).
   * It can be called only with finalized context, i.e. after having called
   * ndpi_finalize_initialization()
   *
   * @par     ndpi_mod = the detection module
   * @return  the number of protocols
   *
   */
  u_int ndpi_get_num_protocols(struct ndpi_detection_module_struct *ndpi_mod);

  /**
   * Get the nDPI version release
   *
   * @return the NDPI_GIT_RELEASE
   *
   */
  char* ndpi_revision(void);

  /**
   * Set the automa for the protocol search
   *
   * @par ndpi_struct = the detection module
   * @par automa      = the automa to match
   *
   */
  void ndpi_set_automa(struct ndpi_detection_module_struct *ndpi_struct,
		       void* automa);

  /* Wrappers functions */
  /**
   * Init Aho-Corasick automata
   *
   * @return  The requested automata, or NULL if an error occurred
   *
   */
  void* ndpi_init_automa(void);
  void *ndpi_init_automa_domain(void);

  /**
   * Free Aho-Corasick automata allocated with ndpi_init_automa();
   *
   * @par     The automata initialized with ndpi_init_automa();
   *
   */
  void ndpi_free_automa(void *_automa);

  /**
   * Add a string to match to an automata
   *
   * @par     The automata initialized with ndpi_init_automa();
   * @par     The (sub)string to search (malloc'ed memory)
   * @par     The number associated with this string
   * @return  0 in case of no error, or -2 if the string has been already added, or -1 if an error occurred.
   *
   */
  int ndpi_add_string_value_to_automa(void *_automa, char *str, u_int32_t num);

  /**
   * Add a string to match to an automata. Same as ndpi_add_string_value_to_automa() with num set to 1
   *
   * @par     The automata initialized with ndpi_init_automa();
   * @par     The (sub)string to search (malloc'ed memory)
   * @return  0 in case of no error, or -1 if an error occurred.
   *
   */
  int ndpi_add_string_to_automa(void *_automa, char *str);

  /**
   * Finalize the automa (necessary before start searching)
   *
   * @par     The automata initialized with ndpi_init_automa();
   *
   */
  void ndpi_finalize_automa(void *_automa);

  /**
   * Get the automa statistics
   *
   * @par     The automata initialized with ndpi_init_automa();
   *
   */

  void ndpi_automa_get_stats(void *_automa, struct ndpi_automa_stats *stats);

  /**
   * Get the statistics of one of the automas used internally by the library
   *
   * @par     ndpi_mod = the detection module
   * @par     automa_type = of which automa we want the stats
   * @par     stats = buffer where to save the stats
   * @return  0 in case of no error, or -1 if an error occurred.
   *
   */

  int ndpi_get_automa_stats(struct ndpi_detection_module_struct *ndpi_struct,
			    automa_type automa_type,
			    struct ndpi_automa_stats *stats);
  /**
   * Add a string to match to an automata
   *
   * @par     The automata initialized with ndpi_init_automa();
   * @par     The (sub)string to search
   * @return  0 in case of match, or -1 if no match, or -2 if an error occurred.
   *
   */
  int ndpi_match_string(void *_automa, char *string_to_match);

  int ndpi_load_ip_category(struct ndpi_detection_module_struct *ndpi_struct,
			    const char *ip_address_and_mask, ndpi_protocol_category_t category,
			    void *user_data);
  int ndpi_load_hostname_category(struct ndpi_detection_module_struct *ndpi_struct,
				  const char *name_to_add, ndpi_protocol_category_t category,
				  ndpi_protocol_breed_t breed);
  int ndpi_load_category(struct ndpi_detection_module_struct *ndpi_struct,
			 const char *ip_or_name, ndpi_protocol_category_t category,
			 ndpi_protocol_breed_t breed,
			 void *user_data);
  int ndpi_enable_loaded_categories(struct ndpi_detection_module_struct *ndpi_struct);
  void* ndpi_find_ipv4_category_userdata(struct ndpi_detection_module_struct *ndpi_str,
					 u_int32_t saddr);
  void* ndpi_find_ipv6_category_userdata(struct ndpi_detection_module_struct *ndpi_str,
					 struct in6_addr *saddr);
  int ndpi_fill_ip_protocol_category(struct ndpi_detection_module_struct *ndpi_struct,
				     struct ndpi_flow_struct *flow,
				     u_int32_t saddr,
				     u_int32_t daddr,
				     ndpi_protocol *ret);
  int ndpi_fill_ipv6_protocol_category(struct ndpi_detection_module_struct *ndpi_str,
				       struct ndpi_flow_struct *flow,
				       struct in6_addr *saddr, struct in6_addr *daddr,
				      ndpi_protocol *ret);
  int ndpi_match_custom_category(struct ndpi_detection_module_struct *ndpi_struct,
				 char *name, u_int name_len, ndpi_protocol_category_t *id,
				 ndpi_protocol_breed_t *breed);
  int ndpi_get_custom_category_match(struct ndpi_detection_module_struct *ndpi_struct,
				     char *name_or_ip, u_int name_len,
				     ndpi_protocol_category_t *category,
				     ndpi_protocol_breed_t *breed);

  u_int16_t ndpi_map_user_proto_id_to_ndpi_id(struct ndpi_detection_module_struct *ndpi_str,
					      u_int16_t user_proto_id);
  u_int16_t ndpi_map_ndpi_id_to_user_proto_id(struct ndpi_detection_module_struct *ndpi_str,
					      u_int16_t ndpi_proto_id);

  /* Tells to called on what l4 protocol given application protocol can be found */
  ndpi_l4_proto_info ndpi_get_l4_proto_info(struct ndpi_detection_module_struct *ndpi_struct, u_int16_t ndpi_proto_id);
  const char* ndpi_get_l4_proto_name(ndpi_l4_proto_info proto);

  u_int16_t ndpi_get_lower_proto(ndpi_master_app_protocol proto);
  u_int16_t ndpi_get_upper_proto(ndpi_master_app_protocol proto);
  bool ndpi_is_proto(ndpi_master_app_protocol proto, u_int16_t p);
  bool ndpi_is_proto_unknown(ndpi_master_app_protocol proto);
  bool ndpi_is_proto_equals(ndpi_master_app_protocol to_check, ndpi_master_app_protocol to_match, bool exact_match_only);
  u_int16_t ndpi_stack_get_lower_proto(struct ndpi_proto_stack *s);
  u_int16_t ndpi_stack_get_upper_proto(struct ndpi_proto_stack *s);
  bool ndpi_stack_contains(struct ndpi_proto_stack *s, u_int16_t proto_id);
  bool ndpi_stack_is_tls_like(struct ndpi_proto_stack *s);
  bool ndpi_stack_is_http_like(struct ndpi_proto_stack *s);

  char *ndpi_stack2str(struct ndpi_detection_module_struct *ndpi_str,
                       struct ndpi_proto_stack *stack, char *buf, u_int buf_len);

  ndpi_proto_defaults_t* ndpi_get_proto_defaults(struct ndpi_detection_module_struct *ndpi_mod);
  u_int ndpi_get_ndpi_detection_module_size(void);

  /* Simple helper to get current time, in sec */
  u_int32_t ndpi_get_current_time(struct ndpi_flow_struct *flow);

  /* LRU cache */
  struct ndpi_lru_cache* ndpi_lru_cache_init(u_int32_t num_entries, u_int32_t ttl, int shared);
  void ndpi_lru_free_cache(struct ndpi_lru_cache *c);
  u_int8_t ndpi_lru_find_cache(struct ndpi_lru_cache *c, u_int64_t key,
			       u_int16_t *value, u_int8_t clean_key_when_found, u_int32_t now_sec);
  void ndpi_lru_add_to_cache(struct ndpi_lru_cache *c, u_int64_t key, u_int16_t value, u_int32_t now_sec);
  void ndpi_lru_get_stats(struct ndpi_lru_cache *c, struct ndpi_lru_cache_stats *stats);

  int ndpi_get_lru_cache_stats(struct ndpi_global_context *g_ctx,
			       struct ndpi_detection_module_struct *ndpi_struct,
			       lru_cache_type cache_type,
			       struct ndpi_lru_cache_stats *stats);

  /**
   * Find a protocol id associated with a string automata
   *
   * @par     The automata initialized with ndpi_init_automa();
   * @par     The (sub)string to search
   * @par     The (sub)string length
   * @par     The protocol id associated with the matched string or 0 id not found.
   * @return  0 in case of match, or -1 if no match, or -2 if an error occurred.
   *
   */
  int ndpi_match_string_protocol_id(void *_automa, char *string_to_match, u_int match_len,
				    u_int16_t *protocol_id,
				    ndpi_protocol_category_t *category,
				    ndpi_protocol_breed_t *breed);

  /**
   * Handle risk exceptions for a flow by unsetting risks that should be ignored
   * based on the exception rules configured via ndpi_load_risk_domain_exceptions().
   *
   * @param ndpi_str The detection module
   * @param flow The flow to process for risk exceptions
   */
  void ndpi_handle_risk_exceptions(struct ndpi_detection_module_struct *ndpi_str,
				   struct ndpi_flow_struct *flow);

  /**
   * Set custom memory allocation functions for nDPI's general memory allocator.
   *
   * @param __ndpi_malloc         Function pointer to the custom malloc implementation
   * @param __ndpi_free           Function pointer to the custom free implementation
   * @param __ndpi_calloc         Function pointer to the custom calloc implementation
   * @param __ndpi_realloc        Function pointer to the custom realloc implementation
   * @param __ndpi_aligned_malloc Function pointer to the custom aligned allocation implementation
   * @param __ndpi_aligned_free   Function pointer to the custom aligned free implementation
   * @param __ndpi_flow_malloc    Function pointer to the custom allocation of flows
   * @param __ndpi_flow_free      Function pointer to the custom free of flows
   *
   * This function is optional, but if used, it MUST be called before ANY other nDPI functions!!
   *
   * The first 4 parameters are mandatory.
   * If you want to set a custom allocator for aligned memory, you must specify `__ndpi_aligned_malloc`
   * and `__ndpi_aligned_free`, both
   * If you want to set a custom allocator for flow memory, you must specify `__ndpi_flow_malloc`
   * and `__ndpi_flow_free`, both
   *
   * Flow memory can use a separate allocator from general memory for better
   * memory management in high-throughput scenarios.
   */

  void ndpi_set_memory_alloction_functions(void *(*__ndpi_malloc)(size_t size),
                                           void (*__ndpi_free)(void *ptr),
                                           void *(*__ndpi_calloc)(size_t nmemb, size_t size),
                                           void *(*__ndpi_realloc)(void *ptr, size_t size),
                                           void *(*__ndpi_aligned_malloc)(size_t alignment, size_t size),
                                           void (*__ndpi_aligned_free)(void *ptr),
                                           void *(*__ndpi_flow_malloc)(size_t size),
                                           void (*__ndpi_flow_free)(void *ptr));

  /**
   * Set a custom debug/logging function for nDPI.
   *
   * @param ndpi_str The detection module
   * @param ndpi_debug_printf Function pointer to the custom debug printf implementation
   */
  void set_ndpi_debug_function(struct ndpi_detection_module_struct *ndpi_str,
			       ndpi_debug_function_ptr ndpi_debug_printf);
  u_int16_t ndpi_get_api_version(void);
  const char *ndpi_get_gcrypt_version(void);

  /**
   * Compute the Community ID hash for an IPv4 flow.
   * Community ID is a standard for flow hashing that enables correlation of
   * network traffic across different monitoring tools.
   * Specification: https://github.com/corelight/community-id-spec
   *
   * @param l4_proto Layer 4 protocol (e.g., IPPROTO_TCP, IPPROTO_UDP)
   * @param src_ip Source IPv4 address in host byte order
   * @param dst_ip Destination IPv4 address in host byte order
   * @param src_port Source port (host byte order), or 0 for non-port protocols
   * @param dst_port Destination port (host byte order), or 0 for non-port protocols
   * @param icmp_type ICMP type (for ICMP), or 0 otherwise
   * @param icmp_code ICMP code (for ICMP), or 0 otherwise
   * @param hash_buf Buffer to store the resulting hash
   * @param hash_buf_len Length of hash_buf (must be at least 20 bytes for SHA1)
   * @return 0 on success, -1 on error
   */
  int ndpi_flowv4_flow_hash(u_int8_t l4_proto, u_int32_t src_ip, u_int32_t dst_ip, u_int16_t src_port, u_int16_t dst_port,
			    u_int8_t icmp_type, u_int8_t icmp_code, u_char *hash_buf, u_int8_t hash_buf_len);

  /**
   * Compute the Community ID hash for an IPv6 flow.
   * Community ID is a standard for flow hashing that enables correlation of
   * network traffic across different monitoring tools.
   * Specification: https://github.com/corelight/community-id-spec
   *
   * @param l4_proto Layer 4 protocol (e.g., IPPROTO_TCP, IPPROTO_UDP)
   * @param src_ip Source IPv6 address
   * @param dst_ip Destination IPv6 address
   * @param src_port Source port (host byte order), or 0 for non-port protocols
   * @param dst_port Destination port (host byte order), or 0 for non-port protocols
   * @param icmp_type ICMPv6 type (for ICMPv6), or 0 otherwise
   * @param icmp_code ICMPv6 code (for ICMPv6), or 0 otherwise
   * @param hash_buf Buffer to store the resulting hash
   * @param hash_buf_len Length of hash_buf (must be at least 20 bytes for SHA1)
   * @return 0 on success, -1 on error
   */
  int ndpi_flowv6_flow_hash(u_int8_t l4_proto, const struct ndpi_in6_addr *src_ip, const struct ndpi_in6_addr *dst_ip,
			    u_int16_t src_port, u_int16_t dst_port, u_int8_t icmp_type, u_int8_t icmp_code,
			    u_char *hash_buf, u_int8_t hash_buf_len);
  u_int8_t ndpi_is_safe_ssl_cipher(u_int32_t cipher);
  const char* ndpi_cipher2str(u_int32_t cipher, char unknown_cipher[8]);
  const char* ndpi_tunnel2str(ndpi_packet_tunnel tt);
  u_int16_t ndpi_guess_host_protocol_id(struct ndpi_detection_module_struct *ndpi_struct,
					struct ndpi_flow_struct *flow);
  int ndpi_has_human_readable_string(char *buffer, u_int buffer_size,
				      u_int8_t min_string_match_len, /* Will return 0 if no string > min_string_match_len have been found */
				      char *outbuf, u_int outbuf_len);
  /* Return a flow info string (summarized). Does only work for DNS/HTTP/TLS/QUIC. */
  const char* ndpi_get_flow_info(struct ndpi_flow_struct const * const flow,
                                 ndpi_protocol const * const l7_protocol);
  char* ndpi_ssl_version2str(char *buf, int buf_len,
                             u_int16_t version, u_int8_t *unknown_tls_version);
  char *ndpi_multimedia_flowtype2str(char *buf, int buf_len, u_int8_t m_types);
  char *ndpi_quic_version2str(char *buf, int buf_len, u_int32_t version);
  int ndpi_netbios_name_interpret(u_char *in, u_int in_len, u_char *out, u_int out_len);
  void ndpi_patchIPv6Address(char *str);
  void ndpi_user_pwd_payload_copy(u_int8_t *dest, u_int dest_len, u_int offset,
				  const u_int8_t *src, u_int src_len);

  u_char* ndpi_base64_decode(const u_char *src, size_t len, size_t *out_len);
  char* ndpi_base64_encode(unsigned char const* bytes_to_encode, size_t in_len); /* NOTE: caller MUST free the returned pointer */

  u_char* ndpi_hex_decode(const u_char *src, size_t len, size_t *out_len);
  char* ndpi_hex_encode(unsigned char const* bytes_to_encode, size_t in_len); /* NOTE: caller MUST free the returned pointer */

  void ndpi_string_sha1_hash(const u_int8_t *message, size_t len, u_char *hash /* 20-bytes */);

  int ndpi_load_ipv4_ptree(struct ndpi_detection_module_struct *ndpi_str,
			   const char *path, u_int16_t protocol_id);
  int ndpi_dpi2json(struct ndpi_detection_module_struct *ndpi_struct,
		    struct ndpi_flow_struct *flow,
		    ndpi_protocol l7_protocol,
		    ndpi_serializer *serializer);
  int ndpi_flow2json(struct ndpi_detection_module_struct *ndpi_struct,
		     struct ndpi_flow_struct *flow,
		     u_int8_t ip_version,
		     u_int8_t l4_protocol,
		     u_int16_t vlan_id,
		     u_int32_t src_v4, u_int32_t dst_v4,
		     struct ndpi_in6_addr *src_v6, struct ndpi_in6_addr *dst_v6,
		     u_int16_t src_port, u_int16_t dst_port,
		     ndpi_protocol l7_protocol,
		     ndpi_serializer *serializer);

  char *ndpi_get_ip_proto_name(u_int16_t ip_proto, char *name, unsigned int name_len);

  const char* ndpi_http_method2str(ndpi_http_method m);
  ndpi_http_method ndpi_http_str2method(const char* method, u_int16_t method_len);

  /* Utility functions to fill prefix (used by the patricia tree) */
  int ndpi_fill_prefix_v4(ndpi_prefix_t *p, const struct in_addr *a, int b, int mb);
  int ndpi_fill_prefix_v6(ndpi_prefix_t *prefix, const struct in6_addr *addr, int bits, int maxbits);
  int ndpi_fill_prefix_mac(ndpi_prefix_t *prefix, u_int8_t *mac, int bits, int maxbits);

  /* Patricia tree API (radix tree supporting IPv4/IPv6/MAC) */
  ndpi_patricia_tree_t *ndpi_patricia_new(u_int16_t maxbits);
  ndpi_patricia_tree_t *ndpi_patricia_clone (const ndpi_patricia_tree_t * const from);
  void ndpi_patricia_destroy(ndpi_patricia_tree_t *patricia, ndpi_void_fn_t func);

  ndpi_patricia_node_t *ndpi_patricia_search_exact(ndpi_patricia_tree_t *patricia, ndpi_prefix_t *prefix);
  ndpi_patricia_node_t *ndpi_patricia_search_best(ndpi_patricia_tree_t *patricia, ndpi_prefix_t *prefix);
  ndpi_patricia_node_t *ndpi_patricia_lookup(ndpi_patricia_tree_t *patricia, ndpi_prefix_t *prefix);
  size_t ndpi_patricia_walk_tree_inorder(ndpi_patricia_tree_t *patricia, ndpi_void_fn3_t func, void *data);
  size_t ndpi_patricia_walk_inorder(ndpi_patricia_node_t *node, ndpi_void_fn3_t func, void *data);
  void ndpi_patricia_remove(ndpi_patricia_tree_t *patricia, ndpi_patricia_node_t *node);
  void ndpi_patricia_process (ndpi_patricia_tree_t *patricia, ndpi_void_fn2_t func);

  void ndpi_patricia_set_node_u64(ndpi_patricia_node_t *node, u_int64_t value);
  u_int64_t ndpi_patricia_get_node_u64(ndpi_patricia_node_t *node);
  void ndpi_patricia_set_node_data(ndpi_patricia_node_t *node, void *data);
  void *ndpi_patricia_get_node_data(ndpi_patricia_node_t *node);
  ndpi_prefix_t *ndpi_patricia_get_node_prefix(ndpi_patricia_node_t *node);
  u_int16_t ndpi_patricia_get_node_bits(ndpi_patricia_node_t *node);
  u_int16_t ndpi_patricia_get_maxbits(ndpi_patricia_tree_t *tree);
  void ndpi_patricia_get_stats(ndpi_patricia_tree_t *tree, struct ndpi_patricia_tree_stats *stats);

  int ndpi_get_patricia_stats(struct ndpi_detection_module_struct *ndpi_struct,
                              ptree_type ptree_type,
                              struct ndpi_patricia_tree_stats *stats);

  /* ptree (trie) API - a wrapper on top of Patricia that seamlessly handle IPv4 and IPv6 */
  ndpi_ptree_t* ndpi_ptree_create(void);
  int ndpi_ptree_insert(ndpi_ptree_t *tree, const ndpi_ip_addr_t *addr, u_int8_t bits, u_int64_t user_data);
  int ndpi_ptree_match_addr(ndpi_ptree_t *tree, const ndpi_ip_addr_t *addr, u_int64_t *user_data);
  int ndpi_load_ptree_file(ndpi_ptree_t *tree, const char *path, u_int16_t protocol_id);
  void ndpi_ptree_destroy(ndpi_ptree_t *tree);

  /* General purpose utilities */
  u_int8_t ndpi_is_public_ipv4(u_int32_t a /* host byte order */);
  u_int64_t ndpi_htonll(u_int64_t v);
  u_int64_t ndpi_ntohll(u_int64_t v);
  u_int8_t ndpi_is_encrypted_proto(struct ndpi_detection_module_struct *ndpi_str, ndpi_master_app_protocol proto);

  /* DGA */
  int ndpi_check_dga_name(struct ndpi_detection_module_struct *ndpi_str,
			  struct ndpi_flow_struct *flow,
			  char *name, u_int8_t is_hostname, u_int8_t check_subproto,
			  u_int8_t flow_fully_classified);

  /* Serializer (supports JSON, TLV, CSV) */

  /**
   * Initialize a serializer handle (allocated by the caller).
   * @param serializer The serializer handle
   * @param fmt The serialization format (ndpi_serialization_format_json, ndpi_serialization_format_tlv, ndpi_serialization_format_csv)
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_init_serializer(ndpi_serializer *serializer, ndpi_serialization_format fmt);

  /**
   * Initialize a serializer handle. Same as ndpi_init_serializer, but with some low-level settings.
   * @param serializer The serializer handle
   * @param fmt The serialization format (ndpi_serialization_format_json, ndpi_serialization_format_tlv, ndpi_serialization_format_csv)
   * @param buffer_size The initial internal buffer_size
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_init_serializer_ll(ndpi_serializer *serializer, ndpi_serialization_format fmt, u_int32_t buffer_size);

  /**
   * Release all allocated data structure.
   * @param serializer The serializer handle
   */
  void ndpi_term_serializer(ndpi_serializer *serializer);

  /**
   * Reset the serializer (cleanup the internal buffer to start a new serialization)
   * @param serializer The serializer handle
   */
  void ndpi_reset_serializer(ndpi_serializer *serializer);

  /**
   * Hint to not create the header (used to avoid creating the header when not used)
   * @param serializer The serializer handle
   */
  void ndpi_serializer_skip_header(ndpi_serializer *serializer);

  /**
   * Serialize a 32-bit unsigned int key and a 32-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_uint32(ndpi_serializer *serializer, u_int32_t key, u_int32_t value);

  /**
   * Serialize a 32-bit unsigned int key and a 64-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_uint64(ndpi_serializer *serializer, u_int32_t key, u_int64_t value);

  /**
   * Serialize a 32-bit unsigned int key and a 32-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_int32(ndpi_serializer *serializer, u_int32_t key, int32_t value);

  /**
   * Serialize a 32-bit unsigned int key and a 64-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_int64(ndpi_serializer *serializer, u_int32_t key, int64_t value);

  /**
   * Serialize a 32-bit unsigned int key and a float value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param format The float value format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_float(ndpi_serializer *serializer, u_int32_t key, float value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize a 32-bit unsigned int key and a double value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param format The double value format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_double(ndpi_serializer *serializer, u_int32_t key, double value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize a 32-bit unsigned int key and a string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_string(ndpi_serializer *serializer, u_int32_t key, const char *value);

  /**
   * Serialize a 32-bit unsigned int key and a boolean value (JSON/CSV only, not supported by TLV)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_boolean(ndpi_serializer *serializer, u_int32_t key, u_int8_t value);

  /**
   * Serialize a 32-bit unsigned int and an unterminated string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param vlen The value length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_uint32_binary(ndpi_serializer *serializer, u_int32_t key, const char *_value, u_int16_t vlen);

  /**
   * Serialize an unterminated string key and a 32-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_int32(ndpi_serializer *_serializer, const char *key, u_int16_t klen, int32_t value);

  /**
   * Serialize a string key and a 32-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_int32(ndpi_serializer *serializer, const char *key, int32_t value);

  /**
   * Serialize an unterminated string key and a 64-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_int64(ndpi_serializer *_serializer, const char *key, u_int16_t klen, int64_t value);

  /**
   * Serialize a string key and a 64-bit signed int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_int64(ndpi_serializer *serializer, const char *key, int64_t value);

  /**
   * Serialize an unterminated string key and a 32-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_uint32(ndpi_serializer *_serializer, const char *key, u_int16_t klen, u_int32_t value);

  /**
   * Serialize a string key and a 32-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_uint32(ndpi_serializer *serializer, const char *key, u_int32_t value);

  /**
   * Serialize a string key and a float value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param format The float format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_uint32_format(ndpi_serializer *serializer, const char *key, u_int32_t value, const char *format);

  /**
   * Serialize an unterminated string key and a 64-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_uint64(ndpi_serializer *_serializer, const char *key, u_int16_t klen, u_int64_t value);

  /**
   * Serialize a string key and a 64-bit unsigned int value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_uint64(ndpi_serializer *serializer, const char *key, u_int64_t value);

  /**
   * Serialize an unterminated string key and an unterminated string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @param vlen The value length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_binary(ndpi_serializer *_serializer, const char *key, u_int16_t klen, const char *_value, u_int16_t vlen);

  /**
   * Serialize a string key and a string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_string(ndpi_serializer *serializer, const char *key, const char *value);

  /**
   * Serialize a string key and a string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param value_len The field value length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_string_len(ndpi_serializer *serializer, const char *key,
				       const char *value, u_int16_t value_len);

  /**
   * Serialize a string key and an unterminated string value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param vlen The value length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_binary(ndpi_serializer *serializer, const char *key, const char *_value, u_int16_t vlen);

  /**
   * Serialize a string key and a raw value (this is a string which is added to the JSON without any quote or escaping)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param vlen The value length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_raw(ndpi_serializer *_serializer, const char *key, const char *_value, u_int16_t vlen);

  /**
   * Serialize an unterminated string key and a float value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @param format The float format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_float(ndpi_serializer *_serializer, const char *key, u_int16_t klen, float value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize an unterminated string key and a double value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @param format The double format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_double(ndpi_serializer *_serializer, const char *key, u_int16_t klen, double value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize a string key and a a float value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param format The float format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_float(ndpi_serializer *serializer, const char *key, float value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize a string key and a a double value
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @param format The double format
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_double(ndpi_serializer *serializer, const char *key, double value, const char *format /* e.f. "%.2f" */);

  /**
   * Serialize an unterminated string key and a boolean value (JSON/CSV only, not supported by TLV)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_binary_boolean(ndpi_serializer *_serializer, const char *key, u_int16_t klen, u_int8_t value);

  /**
   * Serialize a string key and a boolean value (JSON/CSV only, not supported by TLV)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_string_boolean(ndpi_serializer *serializer, const char *key, u_int8_t value);

  /**
   * Serialize a raw record in an array (this is a low-level function and its use is not recommended)
   * @param serializer The serializer handle
   * @param record The record value
   * @param record_len The record length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_raw_record(ndpi_serializer *_serializer, u_char *record, u_int32_t record_len);

  /**
   * Serialize an End-Of-Record (the current object becomes is terminated and added to an array,
   * and a new object is created where the next items will be added)
   * @param serializer The serializer handle
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_end_of_record(ndpi_serializer *serializer);

  /**
   * Serialize the start of a list with an unterminated string key, where the next serialized items
   * will be added (note: keys for the new items are ignored)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_start_of_list_binary(ndpi_serializer *_serializer, const char *key, u_int16_t klen);

  /**
   * Serialize the start of a list, where the next serialized items will be added (note: keys for
   * the new items are ignored)
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_start_of_list(ndpi_serializer *serializer, const char *key);

  /**
   * Serialize the end of a list
   * @param serializer The serializer handle
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_end_of_list(ndpi_serializer *serializer);

  /**
   * Serialize the start of a block with an unterminated string key
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param klen The key length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_start_of_block_binary(ndpi_serializer *_serializer, const char *key, u_int16_t klen);

  /**
   * Serialize the start of a block with a string key
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_start_of_block(ndpi_serializer *serializer, const char *key);

  /**
   * Serialize the start of a block with a numeric key
   * @param serializer The serializer handle
   * @param key The numeric key as 32-bit unsigned integer.
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_start_of_block_uint32(ndpi_serializer *serializer, u_int32_t key);

  /**
   * Serialize the end of a block
   * @param serializer The serializer handle
   * @param key The field name or ID
   * @param value The field value
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serialize_end_of_block(ndpi_serializer *serializer);

  /**
   * Return the serialized buffer
   * @param serializer The serializer handle
   * @param buffer_len The buffer length (out)
   * @return The buffer
   */
  char* ndpi_serializer_get_buffer(ndpi_serializer *serializer, u_int32_t *buffer_len);

  /**
   * Return the current serialized buffer length
   * @param serializer The serializer handle
   * @return The buffer length
   */
  u_int32_t ndpi_serializer_get_buffer_len(ndpi_serializer *serializer);

  /**
   * Return the real internal buffer size (containing the serialized buffer)
   * @param serializer The serializer handle
   * @return The internal buffer size
   */
  u_int32_t ndpi_serializer_get_internal_buffer_size(ndpi_serializer *serializer);

  /**
   * Change the serializer buffer length
   * @param serializer The serializer handle
   * @param l The new buffer length
   * @return 0 on success, a negative number otherwise
   */
  int ndpi_serializer_set_buffer_len(ndpi_serializer *serializer, u_int32_t l);

  /**
   * Return the configured serialization format
   * @param serializer The serializer handle
   * @return The serialization format
   */
  ndpi_serialization_format ndpi_serializer_get_format(ndpi_serializer *serializer);

  /**
   * Set the CSV separator
   * @param serializer The serializer handle
   * @param separator The separator
   */
  void ndpi_serializer_set_csv_separator(ndpi_serializer *serializer, char separator);

  /**
   * Return the header automatically built from keys (CSV only)
   * @param serializer The serializer handle
   * @param buffer_len The buffer length (out)
   * @return The header
   */
  char* ndpi_serializer_get_header(ndpi_serializer *serializer, u_int32_t *buffer_len);

  /**
   * Create a snapshot of the internal buffer for later rollback (ndpi_serializer_rollback_snapshot)
   * @param serializer The serializer handle
   */
  void ndpi_serializer_create_snapshot(ndpi_serializer *serializer);

  /**
   * Rollback to the latest snapshot
   * @param serializer The serializer handle
   */
  void ndpi_serializer_rollback_snapshot(ndpi_serializer *serializer);

  /* Deserializer (supports TLV only) */

  int ndpi_init_deserializer(ndpi_deserializer *deserializer,
			     ndpi_serializer *serializer);
  int ndpi_init_deserializer_buf(ndpi_deserializer *deserializer,
				 u_int8_t *serialized_buffer,
				 u_int32_t serialized_buffer_len);

  ndpi_serialization_format ndpi_deserialize_get_format(ndpi_deserializer *_deserializer);
  ndpi_serialization_type ndpi_deserialize_get_item_type(ndpi_deserializer *deserializer, ndpi_serialization_type *key_type);
  int ndpi_deserialize_next(ndpi_deserializer *deserializer);

  int ndpi_deserialize_key_uint32(ndpi_deserializer *deserializer, u_int32_t *key);
  int ndpi_deserialize_key_string(ndpi_deserializer *deserializer, ndpi_string *key);

  int ndpi_deserialize_value_uint32(ndpi_deserializer *deserializer, u_int32_t *value);
  int ndpi_deserialize_value_uint64(ndpi_deserializer *deserializer, u_int64_t *value);
  int ndpi_deserialize_value_int32(ndpi_deserializer *deserializer, int32_t *value);
  int ndpi_deserialize_value_int64(ndpi_deserializer *deserializer, int64_t *value);
  int ndpi_deserialize_value_float(ndpi_deserializer *deserializer, float *value);
  int ndpi_deserialize_value_double(ndpi_deserializer *deserializer, double *value);
  int ndpi_deserialize_value_string(ndpi_deserializer *deserializer, ndpi_string *value);

  int ndpi_deserialize_clone_item(ndpi_deserializer *deserializer, ndpi_serializer *serializer);
  int ndpi_deserialize_clone_all(ndpi_deserializer *deserializer, ndpi_serializer *serializer);

  /*
   * Escape a string to be suitable for a JSON value, adding double quotes, and terminating the string with a null byte.
   * It is recommended to provide a destination buffer (dst) which is as large as double the source buffer (src) at least.
   * Upon successful return, these functions return the number of characters printed (excluding the null byte used to terminate the string).
   */
  int ndpi_json_string_escape(const char *src, int src_len, char *dst, int dst_max_len);

  /* Data analysis */
  struct ndpi_analyze_struct* ndpi_alloc_data_analysis(u_int16_t _max_series_len);
  struct ndpi_analyze_struct* ndpi_alloc_data_analysis_from_series(const u_int32_t *values, u_int16_t num_values);
  void ndpi_init_data_analysis(struct ndpi_analyze_struct *s, u_int16_t _max_series_len);
  void ndpi_free_data_analysis(struct ndpi_analyze_struct *d, u_int8_t free_pointer);
  void ndpi_reset_data_analysis(struct ndpi_analyze_struct *d);
  void ndpi_data_add_value(struct ndpi_analyze_struct *s, const u_int64_t value);

  /* Sliding-window only */
  float ndpi_data_window_average(struct ndpi_analyze_struct *s);
  float ndpi_data_window_variance(struct ndpi_analyze_struct *s);
  float ndpi_data_window_stddev(struct ndpi_analyze_struct *s);

  /* All data */
  float ndpi_data_average(struct ndpi_analyze_struct *s);
  float ndpi_data_entropy(struct ndpi_analyze_struct *s);
  float ndpi_data_variance(struct ndpi_analyze_struct *s);
  float ndpi_data_stddev(struct ndpi_analyze_struct *s);
  float ndpi_data_mean(struct ndpi_analyze_struct *s);
  float ndpi_data_jitter(struct ndpi_analyze_struct *s);
  u_int64_t ndpi_data_last(struct ndpi_analyze_struct *s);
  u_int64_t ndpi_data_min(struct ndpi_analyze_struct *s);
  u_int64_t ndpi_data_max(struct ndpi_analyze_struct *s);
  float ndpi_data_ratio(u_int32_t sent, u_int32_t rcvd);

  /* ******************************* */

  int ndpi_alloc_rsi(struct ndpi_rsi_struct *s, u_int16_t num_learning_values);
  void  ndpi_free_rsi(struct ndpi_rsi_struct *s);
  float ndpi_rsi_add_value(struct ndpi_rsi_struct *s, const u_int32_t value);

  /* ******************************* */

  int  ndpi_hw_init(struct ndpi_hw_struct *hw, u_int16_t num_periods, u_int8_t additive_seeasonal,
		    double alpha, double beta, double gamma, float significance);
  void ndpi_hw_free(struct ndpi_hw_struct *hw);
  int  ndpi_hw_add_value(struct ndpi_hw_struct *hw, const u_int64_t value, double *forecast,  double *confidence_band);
  void ndpi_hw_reset(struct ndpi_hw_struct *hw);

  /* ******************************* */

  int ndpi_ses_init(struct ndpi_ses_struct *ses, double alpha, float significance);
  int ndpi_ses_add_value(struct ndpi_ses_struct *ses, const double _value, double *forecast, double *confidence_band);
  void ndpi_ses_fitting(double *values, u_int32_t num_values, float *ret_alpha);
  void ndpi_ses_reset(struct ndpi_ses_struct *ses);

  /* ******************************* */

  void ndpi_md5(const u_char *data, size_t data_len, u_char hash[16]);
  void ndpi_sha256(const u_char *data, size_t data_len, u_int8_t sha_hash[32]);

  u_int16_t ndpi_crc16_ccit(const void* data, size_t n_bytes);
  u_int16_t ndpi_crc16_ccit_false(const void *data, size_t n_bytes);
  u_int16_t ndpi_crc16_xmodem(const void *data, size_t n_bytes);
  u_int16_t ndpi_crc16_x25(const void* data, size_t n_bytes);
  u_int32_t ndpi_crc32(const void *data, size_t length, u_int32_t crc);
  u_int32_t ndpi_nearest_power_of_two(u_int32_t x);

  /* ******************************* */

  u_int32_t ndpi_quick_hash(const unsigned char *str, u_int str_len);
  u_int64_t ndpi_quick_hash64(const char *str, u_int str_len);
  u_int32_t ndpi_hash_string(const char *str);
  u_int32_t ndpi_rev_hash_string(const char *str);
  u_int32_t ndpi_hash_string_len(const char *str, u_int len);
  u_int32_t ndpi_murmur_hash(const char *str, u_int str_len);

  /* ******************************* */

  u_int ndpi_hex2bin(u_char *out, u_int out_len, u_char* in, u_int in_len);
  u_int ndpi_bin2hex(u_char *out, u_int out_len, u_char* in, u_int in_len);

  /* ******************************* */

  int ndpi_des_init(struct ndpi_des_struct *des, double alpha, double beta, float significance);
  int ndpi_des_add_value(struct ndpi_des_struct *des, const double _value, double *forecast, double *confidence_band);
  void ndpi_des_fitting(double *values, u_int32_t num_values, float *ret_alpha, float *ret_beta);
  void ndpi_des_reset(struct ndpi_des_struct *des);

  /* ******************************* */

  int   ndpi_jitter_init(struct ndpi_jitter_struct *hw, u_int16_t num_periods);
  void  ndpi_jitter_free(struct ndpi_jitter_struct *hw);
  float ndpi_jitter_add_value(struct ndpi_jitter_struct *s, const float value);

  /* ******************************* */

  const char* ndpi_data_ratio2str(float ratio);

  void ndpi_data_print_window_values(struct ndpi_analyze_struct *s); /* debug */

  ndpi_risk_enum ndpi_validate_url(struct ndpi_detection_module_struct *ndpi_str,
				   struct ndpi_flow_struct *flow, char *url);

  u_int8_t ndpi_is_protocol_detected(ndpi_protocol proto);
  void ndpi_serialize_risk(ndpi_serializer *serializer, ndpi_risk risk);
  void ndpi_serialize_risk_score(ndpi_serializer *serializer, ndpi_risk_enum risk);
  void ndpi_serialize_confidence(ndpi_serializer *serializer, ndpi_confidence_t confidence);
  void ndpi_serialize_proto(struct ndpi_detection_module_struct *ndpi_struct,
                            ndpi_serializer *serializer,
                            ndpi_risk risk,
                            ndpi_confidence_t confidence,
                            ndpi_protocol l7_protocol);
  const char* ndpi_risk2str(ndpi_risk_enum risk);
  const char* ndpi_risk2code(ndpi_risk_enum risk);
  ndpi_risk_enum ndpi_code2risk(const char* risk);
  const char* ndpi_severity2str(ndpi_risk_severity s);
  ndpi_risk_info* ndpi_risk2severity(ndpi_risk_enum risk);
  u_int16_t ndpi_risk2score(ndpi_risk risk,
			    u_int16_t *client_score, u_int16_t *server_score);
  char* print_ndpi_address_port(ndpi_address_port *ap, char *buf, u_int buf_len);
  u_int8_t ndpi_check_issuerdn_risk_exception(struct ndpi_detection_module_struct *ndpi_str,
					      char *issuerDN);
  u_int8_t ndpi_check_flow_risk_exceptions(struct ndpi_detection_module_struct *ndpi_str,
					   u_int num_params,
					   ndpi_risk_params params[]);

  /* ******************************* */

  /* HyperLogLog cardinality estimator [count unique items] */

  /* Memory lifecycle */
  int ndpi_hll_init(struct ndpi_hll *hll, u_int8_t bits);
  void ndpi_hll_destroy(struct ndpi_hll *hll);
  void ndpi_hll_reset(struct ndpi_hll *hll);

  /* Add values */
  int ndpi_hll_add(struct ndpi_hll *hll, const char *data, size_t data_len);
  int ndpi_hll_add_number(struct ndpi_hll *hll, u_int32_t value) ;

  /* Get cardinality estimation */
  double ndpi_hll_count(struct ndpi_hll *hll);

  /* ******************************* */

  /* Count-Min Sketch [count how many times a value has been observed] */

  struct ndpi_cm_sketch *ndpi_cm_sketch_init(u_int16_t depth);
  void ndpi_cm_sketch_add(struct ndpi_cm_sketch *sketch, u_int32_t element);
  u_int32_t ndpi_cm_sketch_count(struct ndpi_cm_sketch *sketch, u_int32_t element);
  void ndpi_cm_sketch_destroy(struct ndpi_cm_sketch *sketch);

  /* ******************************* */

  /* PopCount [count how many bits are set to 1] */

  int ndpi_popcount_init(struct ndpi_popcount *h);
  void ndpi_popcount_count(struct ndpi_popcount *h, const u_int8_t *buf, u_int32_t buf_len);

  /* ******************************* */

  /* Mahalanobis distance (https://en.wikipedia.org/wiki/Mahalanobis_distance) between a point x and a distribution with mean u and inverted covariant matrix i_s */
  float ndpi_mahalanobis_distance(const u_int32_t *x, u_int32_t size, const float *u, const float *i_s);

  /* ******************************* */

  int  ndpi_init_bin(struct ndpi_bin *b, enum ndpi_bin_family f, u_int16_t num_bins);
  void ndpi_free_bin(struct ndpi_bin *b);
  struct ndpi_bin* ndpi_clone_bin(struct ndpi_bin *b);
  void ndpi_inc_bin(struct ndpi_bin *b, u_int16_t slot_id, u_int64_t val);
  void ndpi_set_bin(struct ndpi_bin *b, u_int16_t slot_id, u_int64_t value);
  u_int64_t ndpi_get_bin_value(struct ndpi_bin *b, u_int16_t slot_id);
  void ndpi_reset_bin(struct ndpi_bin *b);
  void ndpi_normalize_bin(struct ndpi_bin *b);
  char* ndpi_print_bin(struct ndpi_bin *b, u_int8_t normalize_first, char *out_buf, u_int out_buf_len);
  float ndpi_bin_similarity(struct ndpi_bin *b1, struct ndpi_bin *b2,
			    u_int8_t normalize_first, float similarity_max_threshold);
  int ndpi_cluster_bins(struct ndpi_bin *bins, u_int16_t num_bins,
			u_int8_t num_clusters, u_int16_t *cluster_ids,
			struct ndpi_bin *centroids);

  /* ******************************* */

  /* create a kd-tree for num_dimensions vector items */
  ndpi_kd_tree* ndpi_kd_create(u_int num_dimensions);

  /* free the ndpi_kd_tree */
  void ndpi_kd_free(ndpi_kd_tree *tree);

  /* remove all the elements from the tree */
  void ndpi_kd_clear(ndpi_kd_tree *tree);

  /* insert a node, specifying its position, and optional data.
     Return true = OK, false otherwise
  */
  bool ndpi_kd_insert(ndpi_kd_tree *tree, const double *data_vector, void *user_data);

  /* Find the nearest node from a given point.
   * This function returns a pointer to a result set with at most one element.
   */
  ndpi_kd_tree_result *ndpi_kd_nearest(ndpi_kd_tree *tree, const double *data_vector);

  /* returns the size of the result set (in elements) */
  u_int32_t ndpi_kd_num_results(ndpi_kd_tree_result *res);

  /* returns the current element and updates user_data with the data put during insert */
  double* ndpi_kd_result_get_item(ndpi_kd_tree_result *res, double **user_data);

  /* frees a result set returned by kd_nearest_range() */
  void ndpi_kd_result_free(ndpi_kd_tree_result *res);

  /* Returns the distance (square root of the individual elements difference) */
  double ndpi_kd_distance(double *a1, double *b2, u_int num_dimensions);

  /* ******************************* */

  /*
    Ball Tree: similar to KD-tree but more efficient with high cardinalities

    - https://en.wikipedia.org/wiki/Ball_tree
    - https://www.geeksforgeeks.org/ball-tree-and-kd-tree-algorithms/
    - https://varshasaini.in/kd-tree-and-ball-tree-knn-algorithm/
    - https://varshasaini.in/k-nearest-neighbor-knn-algorithm-in-machine-learning/

    NOTE:
    with ball tree, data is a vector of vector pointers (no array)
  */
  ndpi_btree* ndpi_btree_init(double **data, u_int32_t n_rows, u_int32_t n_columns);
  ndpi_knn ndpi_btree_query(ndpi_btree *b, double **query_data,
			    u_int32_t query_data_num_rows, u_int32_t query_data_num_columns,
			    u_int32_t max_num_results);
  void ndpi_free_knn(ndpi_knn knn);
  void ndpi_free_btree(ndpi_btree *tree);

  /* ******************************* */

  /*
   * Finds outliers using Z-score
   * Z-Score = (Value - Mean) / StdDev
   *
   * @par    values      = pointer to the individual values to be analyzed [in]
   * @par    outliers    = pointer to a list of outliers identified        [out]
   * @par    num_values  = length of values and outliers that MUST have the same length [in]
   *
   * @return The number of outliers found
  */
  u_int ndpi_find_outliers(u_int32_t *values, bool *outliers, u_int32_t num_values);

  /* ******************************* */

  /*
   * Predicts a value using simple linear regression
   * Z-Score = (Value - Mean) / StdDev
   *
   * @par    values          = pointer to the individual values to be analyzed [in]
   * @par    num_values      = number of 'values' [in]
   * @par    predict_periods = number of periods for which we want to make the prediction [in]
   * @par    prediction      = predicted value after 'predict_periods' [out]
   *
   * @return The number of outliers found
  */
  int ndpi_predict_linear(u_int32_t *values, u_int32_t num_values,
			  u_int32_t predict_periods, u_int32_t *prediction);

  /* ******************************* */

  /*
   * Checks if the two series are correlated using the
   * Pearson correlation coefficient that is a value in the -1..0..+1 range
   * where:
   * -1 < x < 0   Negative correlation (when one changes the other series changes in opposite direction)
   * x = 0        No correlation       (no relationship between the series)
   * 0 < x < 1    Positive correlation (when one changes the other series changes in the same direction)
   * (i.e. when a series increases, the other also increase and vice-versa)
   *
   * @par    values_a   = First series with num_values values
   * @par    values_b   = Second series with num_values values
   * @par    num_values = Number of series entries
   *
   * @return pearson correlation coefficient
   *
   */
  double ndpi_pearson_correlation(u_int32_t *values_a, u_int32_t *values_b, u_int16_t num_values);

  /* ******************************* */

  /*
   * Checks if a specified value is an outlier with respect to past values
   * using the Z-score.
   *
   * @par past_valuea     = List of observed past values (past knowledge)
   * @par num_past_values = Number of observed past values
   * @par value_to_check  = The value to be checked with respect to past values
   * @par threshold       = Threshold on z-score:. Typical values:
   *                        t = 1 - The value to check should not exceed the past values
   *                        t > 1 - The value to check has to be within (t * stddev) boundaries
   * @par lower           - [out] Lower threshold
   * @par upper           - [out] Upper threshold
   *
   * @return true if the specified value is an outlier, false otherwise
   *
   */

  bool ndpi_is_outlier(u_int32_t *past_values, u_int32_t num_past_values,
		       u_int32_t value_to_check, float threshold,
		       float *lower, float *upper);

  /* ******************************* */

  u_int32_t ndpi_quick_16_byte_hash(const u_int8_t *in_16_bytes_long);

  /* ******************************* */

  /**
   * Initialize the hashmap.
   *
   * @par    h            = pointer to the hash map [in, out]
   *
   * @return 0 on success, 1 otherwise
   *
   */
  int ndpi_hash_init(ndpi_str_hash **h);

  /**
   * Free the hashmap.
   *
   * @par    h            = pointer to the hash map [in, out]
   *
   */
  void ndpi_hash_free(ndpi_str_hash **h);

  /**
   * Search for an entry in the hashmap.
   *
   * @par    h            = pointer to the hash map [in]
   * @par    key          = character string (no '\0' required) [in]
   * @par    key_len      = length of the character string @key [in]
   * @par    value        = pointer to a pointer to the value, which contains a
   *                        previously added hash entry [in, out]
   *
   * @return 0 if an entry with that key was found, 1 otherwise
   *
   */
  int ndpi_hash_find_entry(ndpi_str_hash *h, char *key, u_int key_len, u_int64_t *value);

  /**
   * Add an entry to the hashmap.
   *
   * @par    h            = pointer to the hash map [in, out]
   * @par    key          = character string (no '\0' required) [in]
   * @par    key_len      = length of the character string @key [in]
   * @par    value        = value to add [in]
   *
   * @return 0 if the entry was added, 1 otherwise
   *
   */
  int ndpi_hash_add_entry(ndpi_str_hash **h, char *key, u_int8_t key_len, u_int64_t value);

  typedef void (*ndpi_hash_walk_iter)(char *key, u_int64_t value64, void *data);
  void ndpi_hash_walk(ndpi_str_hash **h, ndpi_hash_walk_iter cb, void *data);
  
  void ndpi_hash_get_stats(ndpi_str_hash *h, struct ndpi_str_hash_stats *stats);
  int ndpi_get_hash_stats(struct ndpi_detection_module_struct *ndpi_struct,
                          str_hash_type hash_type,
                          struct ndpi_str_hash_stats *stats);

  /* ******************************* */

  int ndpi_load_geoip(struct ndpi_detection_module_struct *ndpi_str,
		      const char *ip_city_data, const char *ip_as_data);
  void ndpi_free_geoip(struct ndpi_detection_module_struct *ndpi_str);
  int ndpi_get_geoip_asn(struct ndpi_detection_module_struct *ndpi_str,
			 char *ip, u_int32_t *asn);
  int ndpi_get_geoip_aso(struct ndpi_detection_module_struct *ndpi_str,
			 char *ip, char *aso, u_int8_t aso_len);
  int ndpi_get_geoip_country_continent(struct ndpi_detection_module_struct *ndpi_str, char *ip,
				       char *country_code, u_int8_t country_code_len,
				       char *continent, u_int8_t continent_len);
  int ndpi_get_geoip_country_continent_city(struct ndpi_detection_module_struct *ndpi_str, char *ip,
					    char *country_code, u_int8_t country_code_len,
					    char *continent, u_int8_t continent_len,
					    char *city, u_int8_t city_len);

  /* ******************************* */

  char* ndpi_get_flow_name(struct ndpi_flow_struct *flow);

  /* ******************************* */

#ifndef NDPI_SLIM

  /*
    Bitmap based on compressed bitmaps
    implemented by https://roaringbitmap.org

    This is
    - NOT a probabilistic datastructure (i.e. no false positives)
    - mutable (i.e. you can add values at any time)
  */

  ndpi_bitmap* ndpi_bitmap_alloc(void);
  void ndpi_bitmap_free(ndpi_bitmap* b);
  ndpi_bitmap* ndpi_bitmap_copy(ndpi_bitmap* b);
  u_int64_t ndpi_bitmap_cardinality(ndpi_bitmap* b);
  bool ndpi_bitmap_is_empty(ndpi_bitmap* b);
  void ndpi_bitmap_set(ndpi_bitmap* b, u_int64_t value);
  void ndpi_bitmap_unset(ndpi_bitmap* b, u_int64_t value);
  bool ndpi_bitmap_isset(ndpi_bitmap* b, u_int64_t value);

  size_t ndpi_bitmap_serialize(ndpi_bitmap* b, char **buf);
  ndpi_bitmap* ndpi_bitmap_deserialize(char *buf, size_t buf_len);

  void ndpi_bitmap_and(ndpi_bitmap* a, ndpi_bitmap* b_and);
  ndpi_bitmap* ndpi_bitmap_and_alloc(ndpi_bitmap* a, ndpi_bitmap* b_and);
  void ndpi_bitmap_andnot(ndpi_bitmap* a, ndpi_bitmap* b_and);
  void ndpi_bitmap_or(ndpi_bitmap* a, ndpi_bitmap* b_or);
  ndpi_bitmap* ndpi_bitmap_or_alloc(ndpi_bitmap* a, ndpi_bitmap* b_and);
  void ndpi_bitmap_xor(ndpi_bitmap* a, ndpi_bitmap* b_xor);
  void ndpi_bitmap_optimize(ndpi_bitmap* a);

  ndpi_bitmap_iterator* ndpi_bitmap_iterator_alloc(ndpi_bitmap* b);
  void ndpi_bitmap_iterator_free(ndpi_bitmap* b);
  bool ndpi_bitmap_iterator_next(ndpi_bitmap_iterator* i, u_int64_t *value);

  /* ******************************* */

  /*
    Bitmap with 64 bit values based
    on https://github.com/FastFilter/xor_singleheader/tree/master

    This is
    - a probabilistic datastructure !!! (i.e. be prepared to false positives)
    - immutable (i.e. adding keys after a search (i.e. ndpi_bitmap64_fuse_isset)
      is not allowed
   */

  ndpi_bitmap64_fuse* ndpi_bitmap64_fuse_alloc(void);
  bool ndpi_bitmap64_fuse_set(ndpi_bitmap64_fuse *b, u_int64_t value);
  bool ndpi_bitmap64_fuse_compress(ndpi_bitmap64_fuse *b);
  bool ndpi_bitmap64_fuse_isset(ndpi_bitmap64_fuse *b, u_int64_t value);
  void ndpi_bitmap64_fuse_free(ndpi_bitmap64_fuse *b);
  u_int32_t ndpi_bitmap64_fuse_size(ndpi_bitmap64_fuse *b);

  /* ******************************* */

  /*
    Bloom-filter on steroids based on ndpi_bitmap

    The main difference with respect to bloom filters
    is that here the filter cardinality is 2^32 and thus
    not limited as in blooms. This combined with compression
    of ndpi_bitmap creates a memory savvy datastructure at the
    price of little performance penalty due to using a
    compressed datastucture.

    The result is a datatructure with few false positives
    (see https://hur.st/bloomfilter/) computed as

    p = (1 - e(-((k * n)/m)))^k

    number of hash function (k)
    false positive rate (p)
    number of item (n)
    the number of bits (m)

    As in our case m = 2^32, k = 1, for n = 1000000
    (see https://hur.st/bloomfilter/?n=1000000&p=&m=4294967296&k=1)
    p = 2.3 x 10^-4
  */

  ndpi_filter* ndpi_filter_alloc(void);
  bool         ndpi_filter_add(ndpi_filter *f, u_int32_t value); /* returns true on success, false on failure */
  bool         ndpi_filter_add_string(ndpi_filter *f, char *string); /* returns true on success, false on failure */
  bool         ndpi_filter_contains(ndpi_filter *f, u_int32_t value); /* returns true on success, false on failure */
  bool         ndpi_filter_contains_string(ndpi_filter *f, char *string); /* returns true on success, false on failure */
  void         ndpi_filter_free(ndpi_filter *f);
  size_t       ndpi_filter_size(ndpi_filter *f);
  u_int32_t    ndpi_filter_cardinality(ndpi_filter *f);

#endif // NDPI_SLIM

  /* ******************************* */

  /*
    Efficient (space and speed) probabilitic datastructure
    for substring domain matching and classification
  */

  ndpi_domain_classify* ndpi_domain_classify_alloc(void);
  void ndpi_domain_classify_free(ndpi_domain_classify *s);
  u_int32_t ndpi_domain_classify_size(ndpi_domain_classify *s);
  bool ndpi_domain_classify_add(struct ndpi_detection_module_struct *ndpi_mod,
				ndpi_domain_classify *s,
				u_int32_t class_id, char *domain);
  u_int32_t ndpi_domain_classify_add_domains(struct ndpi_detection_module_struct *ndpi_mod,
					     ndpi_domain_classify *s,
					     u_int32_t class_id,
					     char *file_path);
  bool ndpi_domain_classify_hostname(struct ndpi_detection_module_struct *ndpi_mod,
				     ndpi_domain_classify *s,
				     u_int64_t *class_id /* out */,
				     char *hostname);

  /* ******************************* */

  /*
    Similar to ndpi_filter but based on binary search and with the
    ability to store a category per value (as ndpi_domain_classify)
  */
  ndpi_binary_bitmap* ndpi_binary_bitmap_alloc(void);
  bool ndpi_binary_bitmap_set(ndpi_binary_bitmap *b, u_int64_t value, u_int8_t category);
  bool ndpi_binary_bitmap_compress(ndpi_binary_bitmap *b);
  bool ndpi_binary_bitmap_isset(ndpi_binary_bitmap *b, u_int64_t value, u_int8_t *out_category);
  void ndpi_binary_bitmap_free(ndpi_binary_bitmap *b);
  u_int32_t ndpi_binary_bitmap_size(ndpi_binary_bitmap *b);
  u_int32_t ndpi_binary_bitmap_cardinality(ndpi_binary_bitmap *b);

  /* ******************************* */


  /* ******************************* */

  char* ndpi_get_flow_risk_info(struct ndpi_flow_struct *flow,
				char *out, u_int out_len,
				u_int8_t use_json);

  /* ******************************* */

  /**
   * Set user data which can later retrieved with `ndpi_get_user_data()`.
   *
   * @par ndpi_str = the struct created for the protocol detection
   * @par user_data = user data pointer you want to retrieve later with `ndpi_get_user_data()`
   *
   */
  void ndpi_set_user_data(struct ndpi_detection_module_struct *ndpi_str, void *user_data);

  /**
   * Get user data which was previously set with `ndpi_set_user_data()`.
   *
   * @par ndpi_str = the struct created for the protocol detection
   *
   * @return the user data pointer
   *
   */
  void *ndpi_get_user_data(struct ndpi_detection_module_struct *ndpi_str);

  /* ******************************* */

  /**
   * Loads the domain suffixes from the specified path. You need to
   * perform this action once
   *
   * @par ndpi_str = the struct created for the protocol detection
   * @par public_suffix_list_path = path of the public_suffix_list path
   *
   * @return 0 = no error, -1 otherwise
   *
   */
  int ndpi_load_domain_suffixes(struct ndpi_detection_module_struct *ndpi_str,
				char *public_suffix_list_path);

  /**
   * Returns the domain suffix out of the specified hostname.
   * The returned pointer is an offset of the original hostname.
   * Note that you need to call ndpi_load_domain_suffixes() before
   * calling this function.
   *
   * @par ndpi_str = the struct created for the protocol detection
   * @par hostname = the hostname from which the domain name has to be extracted
   * @par suffix_id = the id of the returned domain
   *
   * @return The host domain name suffic or the host itself if not found.
   *
   */
  const char* ndpi_get_host_domain_suffix(struct ndpi_detection_module_struct *ndpi_str,
					  const char *hostname,
					  u_int64_t *suffix_id /* out */);

  /**
   * Returns the domain (including the TLS) suffix out of the specified hostname.
   * The returned pointer is an offset of the original hostname.
   * Note that you need to call ndpi_load_domain_suffixes() before
   * calling this function.
   *
   * @par ndpi_str = the struct created for the protocol detection
   * @par hostname = the hostname from which the domain name has to be extracted
   *
   * @return The host domain name or the host itself if not found.
   *
   */
  const char* ndpi_get_host_domain(struct ndpi_detection_module_struct *ndpi_str,
				   const char *hostname);

  /* ******************************* */

  ndpi_cfg_error ndpi_set_config(struct ndpi_detection_module_struct *ndpi_str,
                                 const char *proto, const char *param, const char *value);
  ndpi_cfg_error ndpi_set_config_u64(struct ndpi_detection_module_struct *ndpi_str,
                                     const char *proto, const char *param, uint64_t value);
  char *ndpi_get_config(struct ndpi_detection_module_struct *ndpi_str,
			const char *proto, const char *param, char *buf, int buf_len);
  char *ndpi_dump_config(struct ndpi_detection_module_struct *ndpi_str,
			 FILE *fd);

  void ndpi_dump_host_based_protocol_id(struct ndpi_detection_module_struct *ndpi_str,
					ndpi_hash_walk_iter walker, void *data);
  void ndpi_dump_host_based_category_id(struct ndpi_detection_module_struct *ndpi_str,
					ndpi_hash_walk_iter walker, void *data);

  /* ******************************* */

  /* Can't call libc functions from kernel space, define some stub instead */

#define ndpi_isalpha(ch) (((ch) >= 'a' && (ch) <= 'z') || ((ch) >= 'A' && (ch) <= 'Z'))
#define ndpi_isdigit(ch) ((ch) >= '0' && (ch) <= '9')
#define ndpi_isalnum(ch) (ndpi_isalpha(ch) != 0 || ndpi_isdigit(ch) != 0)
#define ndpi_isspace(ch) (((ch) >= '\t' && (ch) <= '\r') || ((ch) == ' '))
#define ndpi_isprint(ch) ((ch) >= 0x20 && (ch) <= 0x7e)
#define ndpi_ispunct(ch) (((ch) >= '!' && (ch) <= '/') ||   \
              ((ch) >= ':' && (ch) <= '@') ||   \
              ((ch) >= '[' && (ch) <= '`') ||   \
              ((ch) >= '{' && (ch) <= '~'))

  /* ******************************* */

  int64_t ndpi_strtonum(const char *numstr, int64_t minval, int64_t maxval, const char **errstrp, int base);
  int ndpi_vsnprintf(char * str, size_t size, char const * format, va_list va_args);
  int ndpi_snprintf(char * str, size_t size, char const * format, ...);
  struct tm *ndpi_gmtime_r(const time_t *timep, struct tm *result);
  char* ndpi_strrstr(const char *haystack, const char *needle);
  void *ndpi_memrchr(const void *m, int c, size_t n);
  int ndpi_str_endswith(const char *s, const char *suffix);

  /* ******************************* */

  size_t ndpi_compress_str(const char * in, size_t len, char * out, size_t bufsize);
  size_t ndpi_decompress_str(const char * in, size_t len, char * out, size_t bufsize);

  /* ******************************* */

  /* NOTE
     this function works best if yout have loaded in memory domain
     suffixes using ndpi_load_domain_suffixes()
  */
  u_int ndpi_encode_domain(struct ndpi_detection_module_struct *ndpi_str,
			   char *domain, char *out, u_int out_len);

  /* ******************************* */

  char* ndpi_quick_encrypt(const char *cleartext_msg,
			   u_int16_t cleartext_msg_len,
			   u_int16_t *encrypted_msg_len,
			   u_char encrypt_key[64]);

  char* ndpi_quick_decrypt(const char *encrypted_msg,
			   u_int16_t encrypted_msg_len,
			   u_int16_t *decrypted_msg_len,
			   u_char decrypt_key[64]);

  void ndpi_fill_randombytes(unsigned char *buf,
			     unsigned int buf_len);

  /* ******************************* */

  const char* ndpi_print_os_hint(ndpi_os os_hint);

  /* ******************************* */

  bool ndpi_serialize_flow_fingerprint(struct ndpi_detection_module_struct *ndpi_str,
				       struct ndpi_flow_struct *flow, ndpi_serializer *serializer);

  /* ******************************* */

  /* Address cache API */
  struct ndpi_address_cache* ndpi_init_address_cache(u_int32_t max_num_entries);
  void ndpi_term_address_cache(struct ndpi_address_cache *cache);
  u_int32_t ndpi_address_cache_flush_expired(struct ndpi_address_cache *cache, u_int32_t epoch_now);
  struct ndpi_address_cache_item* ndpi_address_cache_find(struct ndpi_address_cache *cache, ndpi_ip_addr_t ip_addr, u_int32_t epoch_now);
  bool ndpi_address_cache_insert(struct ndpi_address_cache *cache, ndpi_ip_addr_t ip_addr, char *hostname,
				 u_int32_t epoch_now, u_int32_t ttl);
  bool ndpi_address_cache_dump(struct ndpi_address_cache *cache, char *path, u_int32_t epoch_now);
  u_int32_t ndpi_address_cache_restore(struct ndpi_address_cache *cache, char *path, u_int32_t epoch_now);


  bool ndpi_cache_address(struct ndpi_detection_module_struct *ndpi_struct,
			ndpi_ip_addr_t ip_addr, char *hostname,
			  u_int32_t epoch_now, u_int32_t ttl);
  struct ndpi_address_cache_item* ndpi_cache_address_find(struct ndpi_detection_module_struct *ndpi_struct, ndpi_ip_addr_t ip_addr);
  bool ndpi_cache_address_dump(struct ndpi_detection_module_struct *ndpi_struct, char *path, u_int32_t epoch_now);
  u_int32_t ndpi_cache_address_restore(struct ndpi_detection_module_struct *ndpi_struct, char *path, u_int32_t epoch_now);
  u_int32_t ndpi_cache_address_flush_expired(struct ndpi_detection_module_struct *ndpi_struct, u_int32_t epoch_now);

  /* Scaffolding code for triggering risk NDPI_UNRESOLVED_HOSTNAME */
  bool ndpi_cache_hostname_ip(struct ndpi_detection_module_struct *ndpi_struct,
			      ndpi_ip_addr_t *ip_addr, char *hostname);
  bool ndpi_cache_find_hostname_ip(struct ndpi_detection_module_struct *ndpi_struct,
				   ndpi_ip_addr_t *ip_addr, char *hostname);
  void ndpi_cache_hostname_ip_swap(struct ndpi_detection_module_struct *ndpi_struct);
  void ndpi_cache_enable(struct ndpi_detection_module_struct *ndpi_struct);

  /* Protocol normalization functions */
  /**
   * Checks if the specified protocol identifier can be placed only on the master_protocol field of ndpi_master_app_protocol
   * @param ndpi_str nDPI detection module
   * @param proto_id nDPI protocol identifier
   * @return true if proto_id cannot be used s app_protocol but only on master_protocol, false is it can be used on both fields
   */
  bool ndpi_is_master_only_protocol(struct ndpi_detection_module_struct *ndpi_str, u_int16_t proto_id);

  /**
   * Normalizes the ndpi_master_app_protocol by reworking values of the specified proto, placing the master/app protocols
   * in the corresponding protocol fields
   * @param ndpi_str nDPI detection module
   * @param proto_id nDPI protocol identifier
   * @return true if the protocok has been modified/normalized, false if proto has not been modified
   */
  bool ndpi_normalize_protocol(struct ndpi_detection_module_struct *ndpi_str,
			       ndpi_master_app_protocol *proto);

  /* ******************************* */

  const char *ndpi_lru_cache_idx_to_name(lru_cache_type idx);

  /**
   * @brief Finds the first occurrence of the sequence `needle` in the array
   * `haystack`.
   *
   * This function searches for the first occurrence of the sequence `needle` of
   * length `needle_len` in the array `haystack` of length `haystack_len`. If
   * `haystack` or `needle` is `NULL`, or `haystack_len` is less than
   * `needle_len`, or `needle_len` is 0, the function returns `NULL`.
   *
   * For optimization, if `needle_len` is 1, the `memchr` function is used.
   *
   * @param haystack Pointer to the array in which the search is performed.
   * @param haystack_len Length of the `haystack` array.
   * @param needle Pointer to the array to be searched for in `haystack`.
   * @param needle_len Length of the `needle` array.
   * @return Pointer to the first occurrence of `needle` in `haystack` or `NULL`
   * if `needle` is not found.
   */
  void* ndpi_memmem(const void* haystack, size_t haystack_len, const void* needle,
                    size_t needle_len);

  /**
   * @brief Copies src string to dst buffer with length limit
   *
   * Copies the string src into dst buffer, limiting the copy length by dst_len.
   * Handles both null-terminated and non null-terminated strings based on
   * src_len. Ensures null-termination in dst if dst_len > 0.
   *
   * @param dst Destination buffer
   * @param src Source string
   * @param dst_len Size of dst buffer
   * @param src_len Length of src string
   *
   * @return Length of src string
   */
  size_t ndpi_strlcpy(char* dst, const char* src, size_t dst_len, size_t src_len);

  /**
   * @brief Converts a string from ISO 8859 to UTF-8
   *
   * @param in String to convert
   * @param in_len Source string length
   * @param out Destination string buffer (UTF-8)
   * @param out_len Length of destination string buffer. It must be at least (2*in_len)+1
   *
   * @return The destination string buffer
   */
  u_char* ndpi_str_to_utf8(u_char *in, u_int in_len, u_char *out, u_int out_len);

  /**
   * Performs a case-insensitive comparison of two memory regions
   *
   * @par    s1    Pointer to the first memory region
   * @par    s2    Pointer to the second memory region
   * @par    n     Number of bytes to compare
   * @return       < 0 if s1 is less than s2 in a case-insensitive comparison
   *               = 0 if s1 matches s2 in a case-insensitive comparison
   *               > 0 if s1 is greater than s2 in a case-insensitive comparison
   *               If s1 is NULL and s2 is not, returns -1
   *               If s2 is NULL and s1 is not, returns 1
   *               If both are NULL, returns 0
   *
   * This function works similarly to memcmp() but performs case-insensitive
   * comparison.
   */
  int ndpi_memcasecmp(const void *s1, const void *s2, size_t n);


  int ndpi_bitmask_alloc(struct ndpi_bitmask *b, u_int16_t max_bits);
  void ndpi_bitmask_free(struct ndpi_bitmask *b);
  void ndpi_bitmask_set(struct ndpi_bitmask *b, u_int16_t bit);
  void ndpi_bitmask_clear(struct ndpi_bitmask *b, u_int16_t bit);
  int ndpi_bitmask_is_set(const struct ndpi_bitmask *b, u_int16_t bit);
  void ndpi_bitmask_set_all(struct ndpi_bitmask *b);
  void ndpi_bitmask_reset(struct ndpi_bitmask *b);

  bool ndpi_check_is_numeric_ip(char *host);
  u_int16_t ndpi_get_master_proto(struct ndpi_detection_module_struct *ndpi_struct,
				  struct ndpi_flow_struct *flow);

  /* *********************** */

  void ndpi_init_ranking(ndpi_ranking *rank, u_int16_t max_num_items, u_int16_t num_epochs);
  void ndpi_term_ranking(ndpi_ranking *rank);
  bool ndpi_serialize_ranking(ndpi_ranking *rank, const char *path);
  bool ndpi_deserialize_ranking(ndpi_ranking *rank, const char *path);
  void ndpi_print_ranking(ndpi_ranking *rank);
  u_int16_t ndpi_ranking_add_epoch(ndpi_ranking *rank, u_int32_t epoch,
				   ndpi_ranking_epoch_entry *entries,
				   u_int16_t num_epoch_entries,
				   ndpi_ranking_change *curr_ranking,/* Out */
				   ndpi_ranking_change *prev_ranking /* Out */,
				   u_int32_t *prev_ranking_epoch /* Out */);
#ifdef __cplusplus
}
#endif

#endif	/* __NDPI_API_H__ */
