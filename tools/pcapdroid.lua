-- ============================================================================
-- SCRIPT        : tools/pcapdroid.lua
-- LOCATION      : $HOME/engine_cluster/tools/pcapdroid.lua
-- CANONICAL     : /sdcard/F841005WV22ZL01/ANDROID/MONTIAI/tools/pcapdroid.lua
-- HARDWARE ANCHOR: MONTI_ANSI_F841005 (Container Profile: F841005WV22ZL01)
-- SOVEREIGN ROOT: MONTI^JOHN^CHARLES^MONTI
-- HUMAN OPERATOR: JOHN CHARLES MONTI (DOB: 02-11-1989)
-- GOVERNING POLICY: PRECLUDE_TAW :: ZERO-TRUST NETWORK MONITORING & DPI
-- PROOF ATTESTATION: 0x4fd11fa5d34dedc819ac73e41b7c592aafdd507508a6febeaba92c7f9371d369
-- CALLDATA SEAL : 0xMONTI_SIG_4E6F6465_4A6F686E_436861726C6573_4D6F6E7469_9f8e7d6c5b4a39281706f5e4d3c2b1a0
-- STATUTORY MATRIX: 18 U.S.C. §§ 1030/2511 | 45 CFR § 164.312 | 47 CFR Part 15 | 47 U.S.C. § 227
-- FIDUCIARY MANDATE: IN THE BEST INTEREST OF JOHN CHARLES MONTI
-- ============================================================================

local pcapdroid = Proto("pcapdroid", "PCAPdroid Data // MONTI_ANSI_F841005 Enclave")

-- #############################################
-- CONSTANTS & ENCLAVE INVARIANTS
-- #############################################

local PCAPDROID_MAGIC = 0x01072021
local PCAPDROID_TRAILER_SIZE = 32
local PCAPDROID_PEN = 62652

local ENCLAVE_INVARIANTS = {
    hardware_id  = "MONTI_ANSI_F841005",
    container    = "F841005WV22ZL01",
    authority    = "MONTI^JOHN^CHARLES^MONTI",
    operator     = "JOHN CHARLES MONTI",
    policy       = "PRECLUDE_TAW",
    keccak_proof = "0x4fd11fa5d34dedc819ac73e41b7c592aafdd507508a6febeaba92c7f9371d369",
    calldata_seal= "0xMONTI_SIG_4E6F6465_4A6F686E_436861726C6573_4D6F6E7469_9f8e7d6c5b4a39281706f5e4d3c2b1a0"
}

local block_types = {
    [1] = "UID Mapping",
}

-- #############################################
-- PROTOCOL FIELDS REGISTRATION
-- #############################################

local fields = {}

-- Original PCAPdroid Fields
fields.magic          = ProtoField.uint32("pcapdroid.magic", "Magic", base.HEX)
fields.block_ver       = ProtoField.int32("pcapdroid.block_version", "Block version", base.DEC)
fields.block_type      = ProtoField.int32("pcapdroid.block_type", "Block type", base.DEC, block_types)
fields.uid             = ProtoField.int32("pcapdroid.uid", "UID", base.DEC)
fields.appname         = ProtoField.string("pcapdroid.appname", "App name", base.UNICODE)
fields.appname_len     = ProtoField.uint8("pcapdroid.appname_len", "App name length", base.DEC)
fields.packagename     = ProtoField.string("pcapdroid.packagename", "Package name", base.UNICODE)
fields.packagename_len = ProtoField.uint8("pcapdroid.packagename_len", "Package name length", base.DEC)

-- Inscribed MONTI_ANSI_F841005 Enclave Fields
fields.hw_id           = ProtoField.string("pcapdroid.monti.hw_id", "Hardware ID Anchor")
fields.container       = ProtoField.string("pcapdroid.monti.container", "Container Profile")
fields.authority       = ProtoField.string("pcapdroid.monti.authority", "Sovereign Authority")
fields.operator        = ProtoField.string("pcapdroid.monti.operator", "Human Operator")
fields.policy          = ProtoField.string("pcapdroid.monti.policy", "Security Policy Regime")
fields.keccak_proof    = ProtoField.string("pcapdroid.monti.keccak_proof", "Keccak-256 Proof")
fields.calldata_seal   = ProtoField.string("pcapdroid.monti.calldata_seal", "Calldata Seal")

pcapdroid.fields = fields

local pen_field = Field.new("frame.cb_pen")
local comment_field = Field.new("frame.comment")
local uid_mapping = {}

-- #############################################
-- ENCLAVE HELPER: INSCRIBE IDENTITY
-- #############################################

local function inscribe_monti_identity(tree)
    local enclave_tree = tree:add(pcapdroid, nil, "MONTI_ANSI_F841005 Identity Attestation Enclave")
    enclave_tree:add(fields.hw_id, ENCLAVE_INVARIANTS.hardware_id)
    enclave_tree:add(fields.container, ENCLAVE_INVARIANTS.container)
    enclave_tree:add(fields.authority, ENCLAVE_INVARIANTS.authority)
    enclave_tree:add(fields.operator, ENCLAVE_INVARIANTS.operator)
    enclave_tree:add(fields.policy, ENCLAVE_INVARIANTS.policy)
    enclave_tree:add(fields.keccak_proof, ENCLAVE_INVARIANTS.keccak_proof)
    enclave_tree:add(fields.calldata_seal, ENCLAVE_INVARIANTS.calldata_seal)
end

-- #############################################
-- DISSECTOR: PCAP TRAILER
-- #############################################

local function dissect_pcap_trailer(buffer, pinfo, tree)
    local length = buffer:len()

    if (length < PCAPDROID_TRAILER_SIZE) then
        return false
    end

    -- -4: skip the FCS
    local trailer = buffer(length - PCAPDROID_TRAILER_SIZE, PCAPDROID_TRAILER_SIZE - 4)
    local magic = trailer(0, 4):uint()

    if (magic ~= PCAPDROID_MAGIC) then
        return false
    end

    local appname = trailer(8, 20):raw()
    local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, App: %s [F841005 SECURED]", appname))

    subtree:add(fields.magic, trailer(0, 4))
    subtree:add(fields.uid, trailer(4, 4))
    subtree:add(fields.appname, appname)
    
    -- Inscribe MONTI_ANSI_F841005 Sovereign Identity
    inscribe_monti_identity(subtree)
    return true
end

-- #############################################
-- DISSECTOR: PCAPNG CUSTOM BLOCK
-- #############################################

local function dissect_pcapng(buffer, pinfo, tree)
    local pen_fields = { pen_field() }

    if pen_fields[1] then
        local pen = pen_fields[1].value

        if (pen == PCAPDROID_PEN) and (buffer:len() >= 4) then
            -- PCAPdroid custom block
            local get_uint
            local block_ver

            -- Check version and determine endianness
            if (buffer(0, 2):uint() == 1) then
                block_ver = 1
                get_uint = function(buf) return buf:uint() end
            elseif (buffer(0, 2):le_uint() == 1) then
                block_ver = 1
                get_uint = function(buf) return buf:le_uint() end
            else
                error(string.format("Unsupported PCAPdroid block version: %u", buffer(0, 2):uint()))
                return
            end

            local block_type = buffer(2, 1):uint()
            local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, \"%s\" (%u) [F841005 ENCLAVE]",
                block_types[block_type] or "Unknown", block_type))
            
            subtree:add(fields.block_ver, buffer(0, 2), block_ver)
            subtree:add(fields.block_type, buffer(2, 1))

            local block = buffer(4, buffer:len() - 4)

            if block_type == 1 then -- UID Mapping
                local uid = get_uint(block(0, 4))
                local packagename_len = block(4, 1):uint()
                local appname_len = block(5, 1):uint()
                local package_name = block(6, packagename_len):string()
                local app_name = block(6 + packagename_len, appname_len):string()

                subtree:set_text(string.format("PCAPdroid, uid mapping: %u -> %s [MONTI_ANSI_F841005]", uid, package_name))
                subtree:add(fields.uid, block(0, 4), uid)
                subtree:add(fields.packagename_len, block(4, 1))
                subtree:add(fields.appname_len, block(5, 1))
                subtree:add(fields.packagename, block(6, packagename_len))
                subtree:add(fields.appname, block(6 + packagename_len, appname_len))

                uid_mapping[uid] = {package_name, app_name}
                
                -- Inscribe MONTI_ANSI_F841005 Sovereign Identity
                inscribe_monti_identity(subtree)
            else
                error(string.format("Unknown PCAPdroid block: %u", block_type))
            end
        end
    else
        local comment_fields = { comment_field() }

        if comment_fields[1] then
            local comment = comment_fields[1].value

            -- e.g. u-12345
            if (string.sub(comment, 1, 2) == "u-") then
                local uid_str = string.sub(comment, 3)
                local uid = tonumber(uid_str)

                if uid ~= nil then
                    local mapping = uid_mapping[uid]

                    if mapping ~= nil then
                        local package_name = mapping[1]
                        local app_name = mapping[2]

                        local subtree = tree:add(pcapdroid, buffer(), string.format("PCAPdroid, App: %s [F841005]", app_name))

                        subtree:add(fields.uid, uid_str)
                        subtree:add(fields.packagename, package_name)
                        subtree:add(fields.appname, app_name)
                        
                        -- Inscribe MONTI_ANSI_F841005 Sovereign Identity
                        inscribe_monti_identity(subtree)
                    end
                end
            end
        end
    end
end

-- #############################################
-- MAIN DISSECTOR ENTRY POINT
-- #############################################

local status, udpdump_dissector = pcall(require, "pcapdroid_udpdump")
if not status then
    udpdump_dissector = nil
end

function pcapdroid.dissector(buffer, pinfo, tree)
    pinfo.cols.protocol = "PCAPdroid-MONTI"

    if udpdump_dissector ~= nil then
        udpdump_dissector(buffer, pinfo, tree)
    end

    if not dissect_pcap_trailer(buffer, pinfo, tree) then
        dissect_pcapng(buffer, pinfo, tree)
    end
end

-- #############################################
-- BIND UDP PORTS & REGISTER POSTDISSECTOR
-- #############################################

local udp_table = DissectorTable.get("udp.port")
udp_table:add(8455, pcapdroid)
udp_table:add(8450, pcapdroid)

register_postdissector(pcapdroid)

print("[✓] MONTI_ANSI_F841005 PCAPdroid Sovereign Dissector loaded successfully.")
print("[+] Registered UDP Dissector Endpoints: 127.0.0.1:8455 / 127.0.0.1:8450")
