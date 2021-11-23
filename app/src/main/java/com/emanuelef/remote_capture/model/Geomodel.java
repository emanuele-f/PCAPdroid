/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import androidx.annotation.NonNull;

import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;

import java.io.Serializable;

public class Geomodel {
    public static class CountryResult {
        public final Country country;

        @MaxMindDbConstructor
        public CountryResult (
                @MaxMindDbParameter(name="country") Country country
        ) {
            this.country = country;
        }
    }

    public static class Country implements Serializable {
        public final String isoCode;

        // https://db-ip.com/db/format/ip-to-country/mmdb.html
        @MaxMindDbConstructor
        public Country(
                @MaxMindDbParameter(name="iso_code") String isoCode) {
            this.isoCode = isoCode;
        }
    }

    public static class ASN implements Serializable {
        public final long number;
        public final String asname;

        public ASN() {
            number = 0;
            asname = "";
        }

        // https://dev.maxmind.com/geoip/docs/databases/asn?lang=en#blocks-files
        @MaxMindDbConstructor
        public ASN(
                @MaxMindDbParameter(name="autonomous_system_number") long number,
                @MaxMindDbParameter(name="autonomous_system_organization") String asname) {
            this.number = number;
            this.asname = asname;
        }

        public boolean isKnown() {
            return(number != 0);
        }

        @Override @NonNull
        public String toString() {
            if(number == 0)
                return "Unknown ASN";
            return "AS" + number + " - " + asname;
        }
    }
}
