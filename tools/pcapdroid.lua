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
--  Copyright 2021 - Emanuele Faranda
--

pcapdroid = Proto("PCAPdroid", "PCAPdroid data")

-- #############################################

local PCAPDROID_MAGIC = 0x01072021
local PCAPDROID_TRAILER_SIZE = 32
local PCAPDROID_PEN = 62652

local block_types = {
  [1] = "UID Mapping",
}

-- #############################################

local fields = {}

fields.magic   = ProtoField.uint32("pcapdroid.magic", "Magic", base.HEX)
fields.block_ver = ProtoField.int32("pcapdroid.block_version", "Block version", base.DEC)
fields.block_type = ProtoField.int32("pcapdroid.block_type", "Block type", base.DEC, block_types)
fields.uid     = ProtoField.int32("pcapdroid.uid", "UID", base.DEC)
fields.appname = ProtoField.string("pcapdroid.appname", "App name", base.UNICODE)
fields.appname_len     = ProtoField.uint8("pcapdroid.appname_len", "App name length", base.DEC)
fields.packagename     = ProtoField.string("pcapdroid.packagename", "Package name", base.UNICODE)
fields.packagename_len = ProtoField.uint8("pcapdroid.packagename_len", "Package name length", base.DEC)

pcapdroid.fields = fields

local pen_field = Field.new("frame.cb_pen")
local comment_field = Field.new("frame.comment")
local uid_mapping = {}

-- #############################################

local function dissect_pcap_trailer(buffer, pinfo, tree)
  local length = buffer:len()

  if(length < PCAPDROID_TRAILER_SIZE) then
    return false
  end

  -- -4: skip the FCS
  local trailer = buffer(length - PCAPDROID_TRAILER_SIZE, PCAPDROID_TRAILER_SIZE - 4)
  local magic = trailer(0, 4):uint()

  if(magic ~= PCAPDROID_MAGIC) then
    return false
  end

  local appname = trailer(8, 20):raw()
  local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, App: %s", appname))

  subtree:add(fields.magic, trailer(0, 4))
  subtree:add(fields.uid, trailer(4, 4))
  subtree:add(fields.appname, trailer(8, 20))
  return true
end

-- #############################################

local function dissect_pcapng(buffer, pinfo, tree)
  local pen_fields = { pen_field() }

  if pen_fields[1] then
    local pen = pen_fields[1].value

    if (pen == PCAPDROID_PEN) and (buffer:len() >= 4) then
      -- PCAPdroid custom block
      local get_uint
      local block_ver

      -- check version and determine endianess
      if (buffer(0, 2):uint() == 1) then
        block_ver = 1
        get_uint = function(buf)
          return buf:uint()
        end
      elseif (buffer(0, 2):le_uint() == 1) then
        block_ver = 1
        get_uint = function(buf)
          return buf:le_uint()
        end
      else
        error(string.format("Unsupported PCAPdroid block version: %u", buffer(0, 2):uint()))
        return
      end

      local block_type = buffer(2, 1):uint()
      local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, \"%s\" (%u)",
        block_types[block_type] or "Unknown", block_type))
      subtree:add(fields.block_ver, buffer(0, 2), block_ver)
      subtree:add(fields.block_type, buffer(2, 1))

      local block = buffer(4, buffer:len() - 4)

      if block_type == 1 then -- UID Mapping
        -- PCAPdroid uid mapping custom block
        local uid = get_uint(block(0, 4))
        local packagename_len = block(4, 1):uint()
        local appname_len = block(5, 1):uint()
        local package_name = block(6, packagename_len):string()
        local app_name = block(6 + packagename_len, appname_len):string()

        subtree:set_text(string.format("PCAPdroid, uid mapping: %u -> %s", uid, package_name))
        subtree:add(fields.uid, block(0, 4), uid)
        subtree:add(fields.packagename_len, block(4, 1))
        subtree:add(fields.appname_len, block(5, 1))
        subtree:add(fields.packagename, block(6, packagename_len))
        subtree:add(fields.appname, block(6 + packagename_len, appname_len))

        uid_mapping[uid] = {package_name, app_name}
      else
        error(string.format("Unknown PCAPdroid block: %u", block_type))
      end
    end
  else
    local comment_fields = { comment_field() }

    if comment_fields[1] then
      local comment = comment_fields[1].value

      -- e.g. u-12345
      if (string.sub(comment, 0, 2) == "u-") then
        local uid_str = string.sub(comment, 3)
        local uid = tonumber(uid_str)

        if uid ~= nil then
          -- the UID must be mapped
          local mapping = uid_mapping[uid]

          if mapping ~= nil then
            local package_name = mapping[1]
            local app_name = mapping[2]

            -- print(string.format("UID %u - %s %s", uid, package_name, app_name))
            local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, App: %s", app_name))

            subtree:add(fields.uid, uid_str)
            subtree:add(fields.packagename, package_name)
            subtree:add(fields.appname, app_name)
          end
        end
      end
    end
  end
end

-- #############################################

local status, udpdump_dissector = pcall(require, "pcapdroid_udpdump")
if not status then
  udpdump_dissector = nil
end

function pcapdroid.dissector(buffer, pinfo, tree)
  if udpdump_dissector ~= nil then
    udpdump_dissector(buffer, pinfo, tree)
  end

  if not dissect_pcap_trailer(buffer, pinfo, tree) then
    dissect_pcapng(buffer, pinfo, tree)
  end
end

-- #############################################

register_postdissector(pcapdroid)
