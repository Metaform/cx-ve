package com.beardyinc.cxve.infrastructure.cfm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ParticipantProfile {
    private Map<String, List<String>> participantRoles = new HashMap<>();
    private String id;
    private int version;
    private String identifier;
    private List<Vpa> vpas = new ArrayList<>();
    private Map<String, Object> properties = new HashMap<>();
    private Map<String, Object> vpaProperties = new HashMap<>();
    private boolean error;
    private String tenantId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public List<Vpa> getVpas() {
        return vpas;
    }

    public void setVpas(List<Vpa> vpas) {
        this.vpas = vpas;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public Map<String, List<String>> getParticipantRoles() {
        return participantRoles;
    }

    public void setParticipantRoles(Map<String, List<String>> participantRoles) {
        this.participantRoles = participantRoles;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> getVpaProperties() {
        return vpaProperties;
    }

    public String getHolderProcessId() {
        var vpaState = getProperties().get("cfm.vpa.state");
        if(vpaState == null) {
            return null;
        }

        if(vpaState instanceof Map stateMap){
            return ofNullable(stateMap.get("holderPid")).map(Object::toString).orElse(null);
        }
        return null;
    }

    public String getParticipantContextId(){
        var vpaState = getProperties().get("cfm.vpa.state");
        if(vpaState == null) {
            return null;
        }

        if(vpaState instanceof Map stateMap){
            return ofNullable(stateMap.get("participantContextId")).map(Object::toString).orElse(null);
        }
        return null;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public static class Builder {
        private final ParticipantProfile profile = new ParticipantProfile();

        private Builder() {
        }

        public Builder id(String id) {
            profile.id = id;
            return this;
        }

        public Builder version(int version) {
            profile.version = version;
            return this;
        }

        public Builder identifier(String identifier) {
            profile.identifier = identifier;
            return this;
        }

        public Builder participantRoles(Map<String, List<String>> participantRoles) {
            profile.participantRoles = participantRoles;
            return this;
        }

        public Builder participantRole(String key, List<String> roles) {
            profile.participantRoles.put(key, roles);
            return this;
        }

        public Builder vpas(List<Vpa> vpas) {
            profile.vpas = vpas;
            return this;
        }

        public Builder vpa(Vpa vpa) {
            profile.vpas.add(vpa);
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            profile.properties = properties;
            return this;
        }

        public Builder property(String key, Object value) {
            profile.properties.put(key, value);
            return this;
        }

        public Builder vpaProperty(String key, Object value){
            profile.vpaProperties.put(key, value);
            return this;
        }

        public Builder vpaProperties(Map<String, Object> vpaProperties) {
            profile.vpaProperties = vpaProperties;
            return this;
        }

        public Builder error(boolean error) {
            profile.error = error;
            return this;
        }

        public ParticipantProfile build() {
            return profile;
        }
    }

    public static class Vpa {
        private String id;
        private int version;
        private String state;
        private String stateTimestamp;
        private String type;
        private String cellId;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getStateTimestamp() {
            return stateTimestamp;
        }

        public void setStateTimestamp(String stateTimestamp) {
            this.stateTimestamp = stateTimestamp;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCellId() {
            return cellId;
        }

        public void setCellId(String cellId) {
            this.cellId = cellId;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final Vpa vpa = new Vpa();

            private Builder() {
            }

            public Builder id(String id) {
                vpa.id = id;
                return this;
            }

            public Builder version(int version) {
                vpa.version = version;
                return this;
            }

            public Builder state(String state) {
                vpa.state = state;
                return this;
            }

            public Builder stateTimestamp(String stateTimestamp) {
                vpa.stateTimestamp = stateTimestamp;
                return this;
            }

            public Builder type(String type) {
                vpa.type = type;
                return this;
            }

            public Builder cellId(String cellId) {
                vpa.cellId = cellId;
                return this;
            }

            public Vpa build() {
                return vpa;
            }
        }
    }
}
