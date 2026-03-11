package com.visa.nucleus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nucleus")
public class NucleusProperties {

    private Defaults defaults = new Defaults();

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public static class Defaults {

        /** Runtime plugin to use: "tmux" (default) or "docker". */
        private String runtime = "tmux";

        public String getRuntime() {
            return runtime;
        }

        public void setRuntime(String runtime) {
            this.runtime = runtime;
        }
    }
}
