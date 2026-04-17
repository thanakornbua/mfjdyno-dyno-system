#include "config_store.h"
#include "config_validator.h"
#include <Preferences.h>
#include <Arduino.h>

volatile uint32_t g_reinit_flags = 0;

static DynoConfig  s_live;
static DynoConfig  s_staged;
static bool        s_staged_valid   = false;
static uint8_t     s_config_version = 0;
static Preferences s_prefs;

static constexpr char NVS_NS[]  = "dyno";
static constexpr char NVS_KEY[] = "cfg_v1";   // bump when DynoConfig layout changes

namespace ConfigStore {

// ---- Boot --------------------------------------------------------
void init() {
    s_prefs.begin(NVS_NS, /*readOnly=*/false);
    size_t stored = s_prefs.getBytesLength(NVS_KEY);
    if (stored == sizeof(DynoConfig)) {
        s_prefs.getBytes(NVS_KEY, &s_live, sizeof(DynoConfig));
        // Run full validation against itself (no "current" context at boot)
        ValidationResult r = ConfigValidator::validate(s_live, s_live);
        if (r.error != ConfigError::NONE) {
            Serial.println("[cfg] NVS config failed validation — using defaults");
            reset_to_defaults();
        } else {
            Serial.println("[cfg] loaded from NVS");
        }
    } else {
        Serial.println("[cfg] no valid NVS config — using defaults");
        reset_to_defaults();
    }
    s_staged_valid = false;
}

// ---- Live config -------------------------------------------------
const DynoConfig& get() {
    return s_live;
}

// ---- Staging -----------------------------------------------------
void stage(const DynoConfig& cfg) {
    s_staged       = cfg;
    s_staged_valid = true;
}

bool has_staged() {
    return s_staged_valid;
}

const DynoConfig& get_staged() {
    // Caller must check has_staged() first.
    return s_staged;
}

void discard_staged() {
    s_staged_valid = false;
}

// ---- Commit ------------------------------------------------------
uint8_t commit(bool save_to_nvs) {
    if (!s_staged_valid) return 0xFFu;

    uint8_t mask = ConfigValidator::compute_reinit_mask(s_live, s_staged);

    s_live         = s_staged;
    s_staged_valid = false;
    s_config_version++;

    // Post reinit request so main loop can act on it.
    g_reinit_flags |= mask;

    if (save_to_nvs) {
        save();
    }

    Serial.printf("[cfg] commit ok — reinit_mask=0x%02X version=%u\n",
                  mask, s_config_version);
    return mask;
}

// ---- NVS persistence ---------------------------------------------
bool save() {
    size_t written = s_prefs.putBytes(NVS_KEY, &s_live, sizeof(DynoConfig));
    bool ok = (written == sizeof(DynoConfig));
    Serial.printf("[cfg] NVS save %s\n", ok ? "ok" : "FAILED");
    return ok;
}

// ---- Hard reset --------------------------------------------------
void reset_to_defaults() {
    s_live         = DEFAULT_CONFIG;
    s_staged_valid = false;
    s_config_version++;
    // Request full reinit so all peripherals reflect the new defaults.
    g_reinit_flags |= REINIT_ENGINE | REINIT_ENCODER | REINIT_CAN | REINIT_UART;
    Serial.println("[cfg] reset to defaults");
}

// ---- Version counter ---------------------------------------------
uint8_t get_config_version() {
    return s_config_version;
}

} // namespace ConfigStore
