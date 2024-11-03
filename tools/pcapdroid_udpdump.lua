--
--  This file is part of PCAPdroid.
--
--  PCAPdroid is free software: you can redistribute it and/or modify
--  it under the terms of the GNU General Public License as published by
--  the Free Software Foundation, either version 3 of the License, or
--  (at your option) any later version.
--
--  PCAPdroid is distributed in the hope that it will be useful,
--  but WITHOUT ANY WARRANTY; without even the implied warranty of
--  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
--  GNU General Public License for more details.
--
--  You should have received a copy of the GNU General Public License
--  along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
--
--  Copyright 2024 - Emanuele Faranda
--

-- A dissector which allows capturing PCAPdroid traffic via the Wireshark
-- udpdump interface. PCAPdroid needs to be configured in UDP dump mode,
-- with target port 5555.
--
-- Place this script in the same directory as pcapdroid.lua
--
-- NOTE: decrypting PCAPNG is not currently supported

local PCAP_HEADER_LEN = 16
local PCAP_REC_LEN = 16
local PCAPNG_SHB_LEN = 24
local PCAPNG_EPK_LEN = 28

local ip_dissector = Dissector.get("ip")
local eth_dissector = Dissector.get("eth_withoutfcs")
local pcap_dissector = Dissector.get("file-pcap")
local pcapng_dissector = Dissector.get("file-pcapng")
local exported_pdu_data = Field.new("exported_pdu.exported_pdu")

function udpdump_dissector(buffer, pinfo, tree)
  local epd_fields = { exported_pdu_data() }

  for i in pairs(epd_fields) do
    local data = epd_fields[i].value
    local len = data:len()

    if len >= PCAP_HEADER_LEN then
      -- check if it's the PCAP header
      if data:uint(0, 4) == 0xd4c3b2a1 then
        pcap_dissector:call(data:tvb(), pinfo, tree)
        return
      end
    end

    if len >= PCAPNG_SHB_LEN then
      -- check if it's the PCAPNG section header block
      if (data:uint(0, 4) == 0x0A0D0D0A) and (data:uint(8, 4) == 0x4D3C2B1A) then
        pcapng_dissector:call(data:tvb(), pinfo, tree)
        return
      end
    end

    if len > PCAPNG_EPK_LEN then
      -- check if it's the PCAPNG enhanced packet block
      if ((data:uint(0, 4) == 0x06000000) and (data:uint(8, 4) == 0)) then
        local pcap_data = data:tvb()(PCAPNG_EPK_LEN)

        ip_dissector:call(pcap_data:tvb(), pinfo, tree)
        return
      end
    end

    if len > PCAP_REC_LEN then
      -- guess the linktype (it's ethernet if the PCAPdroid trailer is enabled)
      local ipver = data:get_index(PCAP_REC_LEN)
      local pcap_data = data:tvb()(PCAP_REC_LEN)

      if ((ipver == 0x45) or (ipver == 0x60)) then
        -- IPv4/v6
        ip_dissector:call(pcap_data:tvb(), pinfo, tree)
      else
        -- Ethernet
        eth_dissector:call(pcap_data:tvb(), pinfo, tree)
      end
    end
  end
end

return udpdump_dissector
