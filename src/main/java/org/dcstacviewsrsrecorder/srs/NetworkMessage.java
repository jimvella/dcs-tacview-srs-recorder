package org.dcstacviewsrsrecorder.srs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategy.UpperCamelCaseStrategy.class)
public class NetworkMessage {

    private final SRClient client;
    private final MessageType msgType;
    private final Map<String, String> serverSettings;
    private final String version;

    public NetworkMessage(SRClient client, MessageType msgType, Map<String, String> serverSettings, String version) {
        this.client = client;
        this.msgType = msgType;
        this.serverSettings = serverSettings;
        this.version = version;
    }

    public SRClient getClient() {
        return client;
    }

    public MessageType getMsgType() {
        return msgType;
    }

    public Map<String, String> getServerSettings() {
        return serverSettings;
    }

    public String getVersion() {
        return version;
    }

    @JsonNaming(PropertyNamingStrategy.UpperCamelCaseStrategy.class)
    public static class SRClient {
        private final String clientGuid;
        private final String name;
        private final DCSPlayerRadioInfo radioInfo;
        private final int coalition;
        private final DCSLatLngPosition latLngPosition;

        public SRClient(String clientGuid, String name, DCSPlayerRadioInfo radioInfo, int coalition, DCSLatLngPosition latLngPosition) {
            this.clientGuid = clientGuid;
            this.name = name;
            this.radioInfo = radioInfo;
            this.coalition = coalition;
            this.latLngPosition = latLngPosition;
        }

        public String getClientGuid() {
            return clientGuid;
        }

        public String getName() {
            return name;
        }

        public DCSPlayerRadioInfo getRadioInfo() {
            return radioInfo;
        }

        public int getCoalition() {
            return coalition;
        }

        public DCSLatLngPosition getLatLngPosition() {
            return latLngPosition;
        }
    }

    public static class DCSPlayerRadioInfo {
        private final String name;
        private final boolean ptt;
        private final List<RadioInformation> radios; //11 (10 + intercom)
        private final int control;
        private final int selected;
        private final String unit;
        private final int unitId;
        private final boolean simultaneousTransmission;

        public DCSPlayerRadioInfo(String name, boolean ptt, List<RadioInformation> radios, int control, int selected, String unit, int unitId, boolean simultaneousTransmission) {
            this.name = name;
            this.ptt = ptt;
            this.radios = radios;
            this.control = control;
            this.selected = selected;
            this.unit = unit;
            this.unitId = unitId;
            this.simultaneousTransmission = simultaneousTransmission;
        }

        public String getName() {
            return name;
        }

        public boolean isPtt() {
            return ptt;
        }

        public List<RadioInformation> getRadios() {
            return radios;
        }

        public int getControl() {
            return control;
        }

        public int getSelected() {
            return selected;
        }

        public String getUnit() {
            return unit;
        }

        public int getUnitId() {
            return unitId;
        }

        public boolean isSimultaneousTransmission() {
            return simultaneousTransmission;
        }
    }

    public static class RadioInformation {

        public final Boolean enc; // = false; // encryption enabled
        public final Integer encKey; // = 0;
        public final EncryptionMode encMode; // = EncryptionMode.NO_ENCRYPTION;
        public final Double freqMax; // = 1;
        public final Double freqMin; // = 1;
        public final Double freq; // = 1;
        public final Modulation modulation; // = Modulation.DISABLED;
        public final String name ; //= "";
        public final Double secFreq; // = 1;
        public final Double volume; // = 1.0f;
        public final FreqMode freqMode; // = FreqMode.COCKPIT;
        public final FreqMode guardFreqMode; // = FreqMode.COCKPIT;
        public final VolumeMode volMode; // = VolumeMode.COCKPIT;
        public final Boolean expansion; // = false;
        public final Integer channel; // = -1;
        public final Boolean simul; // = false;

        /*
         {
            "enc" : false,
            "encKey" : 0,
            "freq" : 1.0,
            "modulation" : 3,
            "secFreq" : 1.0,
            "retransmit" : false
          },
         */
        @JsonIgnore
        public static final RadioInformation DEFAULT = new RadioInformation(
                false,
                0,
                null,
                null,
                null,
                1d,
                //305000000d,
                Modulation.DISABLED,
                //Modulation.AM,
                null,
                1d,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        public RadioInformation(Boolean enc, Integer encKey, EncryptionMode encMode, Double freqMax, Double freqMin, Double freq, Modulation modulation, String name, Double secFreq, Double volume, FreqMode freqMode, FreqMode guardFreqMode, VolumeMode volMode, Boolean expansion, Integer channel, Boolean simul) {
            this.enc = enc;
            this.encKey = encKey;
            this.encMode = encMode;
            this.freqMax = freqMax;
            this.freqMin = freqMin;
            this.freq = freq;
            this.modulation = modulation;
            this.name = name;
            this.secFreq = secFreq;
            this.volume = volume;
            this.freqMode = freqMode;
            this.guardFreqMode = guardFreqMode;
            this.volMode = volMode;
            this.expansion = expansion;
            this.channel = channel;
            this.simul = simul;
        }

        public RadioInformation withFrequency(Double frequency) {
            return new RadioInformation(
                    enc,
                    encKey,
                    encMode,
                    freqMax,
                    freqMin,
                    frequency,
                    modulation,
                    name,
                    secFreq,
                    volume,
                    freqMode,
                    guardFreqMode,
                    volMode,
                    expansion,
                    channel,
                    simul
            );
        }

        public RadioInformation withModulation(Modulation modulation) {
            return new RadioInformation(
                    enc,
                    encKey,
                    encMode,
                    freqMax,
                    freqMin,
                    freq,
                    modulation,
                    name,
                    secFreq,
                    volume,
                    freqMode,
                    guardFreqMode,
                    volMode,
                    expansion,
                    channel,
                    simul
            );
        }

        public Boolean isEnc() {
            return enc;
        }

        public Integer getEncKey() {
            return encKey;
        }

        public EncryptionMode getEncMode() {
            return encMode;
        }

        public Double getFreqMax() {
            return freqMax;
        }

        public Double getFreqMin() {
            return freqMin;
        }

        public Double getFreq() {
            return freq;
        }

        public Modulation getModulation() {
            return modulation;
        }

        public String getName() {
            return name;
        }

        public Double getSecFreq() {
            return secFreq;
        }

        public Double getVolume() {
            return volume;
        }

        public FreqMode getFreqMode() {
            return freqMode;
        }

        public FreqMode getGuardFreqMode() {
            return guardFreqMode;
        }

        public VolumeMode getVolMode() {
            return volMode;
        }

        public Boolean isExpansion() {
            return expansion;
        }

        public Integer getChannel() {
            return channel;
        }

        public Boolean isSimul() {
            return simul;
        }

        public enum EncryptionMode {
            NO_ENCRYPTION,
            ENCRYPTION_JUST_OVERLAY,
            ENCRYPTION_FULL,
            ENCRYPTION_COCKPIT_TOGGLE_OVERLAY_CODE;

            @JsonValue
            public int toValue() {
                return ordinal();
            }

            // 0  is no controls
            // 1 is FC3 Gui Toggle + Gui Enc key setting
            // 2 is InCockpit toggle + Incockpit Enc setting
            // 3 is Incockpit toggle + Gui Enc Key setting
        }

        public enum VolumeMode {
            COCKPIT,
            OVERLAY;

            @JsonValue
            public int toValue() {
                return ordinal();
            }
        }

        public enum FreqMode {
            COCKPIT,
            OVERLAY;

            @JsonValue
            public int toValue() {
                return ordinal();
            }
        }

        public enum RetransmitMode {
            COCKPIT,
            OVERLAY,
            DISABLED;

            @JsonValue
            public int toValue() {
                return ordinal();
            }
        }

        public enum Modulation {
            AM,
            FM,
            INTERCOM,
            DISABLED,
            HAVEQUICK,
            SATCOM,
            MIDS;

            @JsonValue
            public int toValue() {
                return ordinal();
            }
        }
    }

    public static class DCSLatLngPosition {
        private final double lat;
        private final double lng;
        private final double alt;

        public DCSLatLngPosition(double lat, double lng, double alt) {
            this.lat = lat;
            this.lng = lng;
            this.alt = alt;
        }

        public double getLat() {
            return lat;
        }

        public double getLng() {
            return lng;
        }

        public double getAlt() {
            return alt;
        }
    }

    public enum MessageType
    {
        UPDATE, //META Data update - No Radio Information
        PING,
        SYNC,
        RADIO_UPDATE, //Only received server side
        SERVER_SETTINGS,
        CLIENT_DISCONNECT, // Client disconnected
        VERSION_MISMATCH,
        EXTERNAL_AWACS_MODE_PASSWORD, // Received server side to "authenticate"/pick side for external AWACS mode
        EXTERNAL_AWACS_MODE_DISCONNECT; // Received server side on "voluntary" disconnect by the client (without closing the server connection)

        @JsonValue
        public int toValue() {
            return ordinal();
        }
    }
}
