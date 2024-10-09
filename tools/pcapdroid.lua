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

-- #############################################

local fields = {}

fields.magic   = ProtoField.uint32("pcapdroid.magic", "Magic", base.HEX)
fields.uid     = ProtoField.int32("pcapdroid.uid", "UID", base.DEC)
fields.appname = ProtoField.string("pcapdroid.appname", "App name", base.UNICODE)

pcapdroid.fields = fields

-- #############################################

function pcapdroid.dissector(buffer, pinfo, tree)
  local length = buffer:len()

  if(length < PCAPDROID_TRAILER_SIZE) then
    return
  end

  -- -4: skip the FCS
  local trailer = buffer(length - PCAPDROID_TRAILER_SIZE, PCAPDROID_TRAILER_SIZE - 4)
  local magic = trailer(0, 4):uint()

  if(magic ~= PCAPDROID_MAGIC) then
    return
  end

  local appname = trailer(8, 20):raw()
  local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, App: %s", appname))

  subtree:add(fields.magic, trailer(0, 4))
  subtree:add(fields.uid, trailer(4, 4))
  subtree:add(fields.appname, trailer(8, 20))
end

-- #############################################

register_postdissector(pcapdroid)
