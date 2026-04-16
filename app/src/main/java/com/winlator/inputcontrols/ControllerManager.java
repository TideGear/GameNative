package com.winlator.inputcontrols;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.preference.PreferenceManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import app.gamenative.PrefManager;

import com.winlator.winhandler.WinHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ControllerManager {

    @SuppressLint("StaticFieldLeak")
    private static ControllerManager instance;


    /** Returns the singleton instance, creating it on first access. */
    public static synchronized ControllerManager getInstance() {
        if (instance == null) {
            instance = new ControllerManager();
        }
        return instance;
    }

    private ControllerManager() {
        // Private constructor to prevent direct instantiation.
    }

    // --- Core Properties ---
    private Context context;
    private SharedPreferences preferences;
    private InputManager inputManager;

    // Guards detectedDevices, slotAssignments, and enabledSlots against concurrent
    // access from the UI thread (scanForDevices, autoAssignDevice, …) and the
    // rumble poller thread (getAssignedDeviceForSlot, getDetectedDevices).
    private final Object deviceStateLock = new Object();

    // This list will hold all physical game controllers detected by Android.
    private final List<InputDevice> detectedDevices = new ArrayList<>();

    // This maps a player slot (0-3) to the unique identifier of the physical device.
    // e.g., key=0, value="vendor_123_product_456"
    private final SparseArray<String> slotAssignments = new SparseArray<>();

    // This tracks which of the 4 player slots are enabled by the user.
    private final boolean[] enabledSlots = new boolean[WinHandler.MAX_PLAYERS];

    public static final String PREF_PLAYER_SLOT_PREFIX = "controller_slot_";
    public static final String PREF_ENABLED_SLOTS_PREFIX = "enabled_slot_";


    /**
     * Initializes the manager. This must be called once from the main application context.
     * @param context The application context.
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.inputManager = (InputManager) this.context.getSystemService(Context.INPUT_SERVICE);

        // On startup, we load saved settings and scan for connected devices.
        loadAssignments();
        scanForDevices();
    }




    /**
     * Scans for all physically connected game controllers and updates the internal list.
     * After scanning, evicts stale (disconnected) slot assignments and compacts the
     * remaining connected devices to the lowest-numbered slots so that e.g. a lone DS4
     * always occupies slot 0 regardless of historical assignment order.
     */
    public void scanForDevices() {
        synchronized (deviceStateLock) {
            detectedDevices.clear();
            int[] deviceIds = inputManager.getInputDeviceIds();
            for (int deviceId : deviceIds) {
                InputDevice device = inputManager.getInputDevice(deviceId);
                if (device != null && !device.isVirtual() && isGameController(device)) {
                    detectedDevices.add(device);
                }
            }
            evictDisconnectedAndCompact();
        }
    }

    /**
     * Removes slot assignments for devices that are no longer connected,
     * then shifts the remaining assignments down to fill from slot 0.
     * This prevents a single connected controller from being stuck at a
     * high slot number while evshim only reads slot 0.
     */
    private void evictDisconnectedAndCompact() {
        Set<String> connectedIds = new HashSet<>();
        for (InputDevice dev : detectedDevices) {
            String id = getDeviceIdentifier(dev);
            if (id != null) connectedIds.add(id);
        }

        List<String> keptIdentifiers = new ArrayList<>();
        List<Boolean> keptEnabled = new ArrayList<>();
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            String identifier = slotAssignments.get(i);
            if (identifier != null) {
                if (connectedIds.contains(identifier)) {
                    keptIdentifiers.add(identifier);
                    keptEnabled.add(enabledSlots[i]);
                } else {
                    android.util.Log.i("ControllerSlot",
                            "evicting stale slot=" + i + " identifier=" + identifier);
                }
            }
        }

        slotAssignments.clear();
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            if (i < keptIdentifiers.size()) {
                slotAssignments.put(i, keptIdentifiers.get(i));
                enabledSlots[i] = keptEnabled.get(i);
            } else {
                enabledSlots[i] = false;
            }
        }

        saveAssignments();
    }

    /**
     * Loads the saved player slot assignments and enabled states from SharedPreferences.
     */
    private void loadAssignments() {
        slotAssignments.clear();
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            String deviceIdentifier = preferences.getString(prefKey, null);
            if (deviceIdentifier != null) {
                slotAssignments.put(i, deviceIdentifier);
            }

            String enabledKey = PREF_ENABLED_SLOTS_PREFIX + i;
            enabledSlots[i] = preferences.getBoolean(enabledKey, i == 0);
        }
    }

    /**
     * Saves the current player slot assignments and enabled states to SharedPreferences.
     * Takes a consistent snapshot under deviceStateLock before writing, so concurrent
     * mutations on the rumble-poller or UI thread cannot produce a torn save.
     * SharedPreferences.apply() is called outside the lock to avoid holding it during I/O.
     */
    public void saveAssignments() {
        // Snapshot mutable state under lock for a consistent view.
        String[] identifiers = new String[WinHandler.MAX_PLAYERS];
        boolean[] enabled = new boolean[WinHandler.MAX_PLAYERS];
        synchronized (deviceStateLock) {
            for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
                identifiers[i] = slotAssignments.get(i);
                enabled[i] = enabledSlots[i];
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            if (identifiers[i] != null) {
                editor.putString(prefKey, identifiers[i]);
            } else {
                editor.remove(prefKey);
            }
            editor.putBoolean(PREF_ENABLED_SLOTS_PREFIX + i, enabled[i]);
        }
        editor.apply();
    }

// --- Helper & Getter Methods ---

    /**
     * Checks if a device is a gamepad or joystick.
     * @param device The InputDevice to check.
     * @return True if the device is a game controller.
     */
    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;

        boolean isGamepad = device.supportsSource(InputDevice.SOURCE_GAMEPAD);
        boolean isJoystick = device.supportsSource(InputDevice.SOURCE_JOYSTICK);

        boolean hasAxes =
                device.getMotionRange(android.view.MotionEvent.AXIS_X) != null ||
                        device.getMotionRange(android.view.MotionEvent.AXIS_Y) != null;

        boolean[] hasGamepadKeysArray = device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y
        );

        boolean hasGamepadKeys = false;
        for (boolean hasKey : hasGamepadKeysArray) {
            if (hasKey) {
                hasGamepadKeys = true;
                break;
            }
        }

        return (isGamepad && hasGamepadKeys) ||
                (isJoystick && hasAxes);
    }

    /**
     * Creates a stable, unique identifier string for a given device.
     * This is used for saving and loading assignments.
     * @param device The InputDevice.
     * @return A unique identifier string.
     */
    public static String getDeviceIdentifier(InputDevice device) {
        if (device == null) return null;
        // getDescriptor() is stable across reconnects and unique per physical device
        // even when multiple identical-model controllers are connected. Available since API 16.
        return device.getDescriptor();
    }

    /**
     * Returns the list of all detected physical game controllers.
     */
    public List<InputDevice> getDetectedDevices() {
        synchronized (deviceStateLock) {
            return new ArrayList<>(detectedDevices);
        }
    }

    /**
     * Returns the number of player slots the user has enabled.
     */
    public int getEnabledPlayerCount() {
        synchronized (deviceStateLock) {
            int count = 0;
            for (boolean enabled : enabledSlots) {
                if (enabled) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Assigns a physical device to a specific player slot.
     * This method handles un-assigning the device from any other slot it might have been in.
     * @param slotIndex The player slot to assign to (0-3).
     * @param device The physical InputDevice to assign.
     */
    public void assignDeviceToSlot(int slotIndex, InputDevice device) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;

        String newDeviceIdentifier = getDeviceIdentifier(device);
        if (newDeviceIdentifier == null) return;

        synchronized (deviceStateLock) {
            for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
                if (newDeviceIdentifier.equals(slotAssignments.get(i))) {
                    slotAssignments.remove(i);
                }
            }
            slotAssignments.put(slotIndex, newDeviceIdentifier);
        }
        saveAssignments();
    }

    /**
     * Clears any device assignment for the given player slot.
     * @param slotIndex The player slot to un-assign (0-3).
     */
    public void unassignSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;
        synchronized (deviceStateLock) {
            slotAssignments.remove(slotIndex);
        }
        saveAssignments();
    }

    /**
     * Finds which player slot a given device is assigned to.
     * @param deviceId The ID of the physical device.
     * @return The player slot index (0-3), or -1 if the device is not assigned.
     */
    public int getSlotForDevice(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        String deviceIdentifier = getDeviceIdentifier(device);
        if (deviceIdentifier == null) return -1;

        synchronized (deviceStateLock) {
            for (int i = 0; i < slotAssignments.size(); i++) {
                int key = slotAssignments.keyAt(i);
                String value = slotAssignments.valueAt(i);
                if (deviceIdentifier.equals(value)) {
                    return key;
                }
            }
        }

        return -1;
    }


    /**
     * Gets the InputDevice object that is currently assigned to a specific player slot.
     * @param slotIndex The player slot (0-3).
     * @return The assigned InputDevice, or null if no device is assigned or if the device is not currently connected.
     */
    public InputDevice getAssignedDeviceForSlot(int slotIndex) {
        synchronized (deviceStateLock) {
            String assignedIdentifier = slotAssignments.get(slotIndex);
            if (assignedIdentifier == null) return null;

            for (InputDevice device : detectedDevices) {
                if (assignedIdentifier.equals(getDeviceIdentifier(device))) {
                    return device;
                }
            }

            return null;
        }
    }

    /**
     * Sets whether a player slot is enabled ("Connected").
     * @param slotIndex The player slot (0-3).
     * @param isEnabled The new enabled state.
     */
    public void setSlotEnabled(int slotIndex, boolean isEnabled) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;
        synchronized (deviceStateLock) {
            enabledSlots[slotIndex] = isEnabled;
        }
        saveAssignments();
    }

    /** Returns whether the given player slot is enabled. */
    public boolean isSlotEnabled(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return false;
        synchronized (deviceStateLock) {
            return enabledSlots[slotIndex];
        }
    }

    /**
     * Auto-assigns a device to the first available slot.
     * If the device is already assigned, returns its existing slot.
     * @param deviceId The Android device ID from the input event.
     * @return The slot index (0-3), or -1 if no slot available or device is not a controller.
     */
    public int autoAssignDevice(int deviceId) {
        int existingSlot = getSlotForDevice(deviceId);
        if (existingSlot >= 0) {
            return isSlotEnabled(existingSlot) ? existingSlot : -1;
        }

        InputDevice device = inputManager.getInputDevice(deviceId);
        if (device == null || !isGameController(device)) {
            return -1;
        }

        int assignedSlot = -1;
        synchronized (deviceStateLock) {
            // Keep detectedDevices in sync so getAssignedDeviceForSlot can
            // resolve this device without waiting for the next scanForDevices().
            String identifier = getDeviceIdentifier(device);
            if (identifier == null) return -1;
            boolean alreadyDetected = false;
            for (InputDevice d : detectedDevices) {
                if (identifier.equals(getDeviceIdentifier(d))) { alreadyDetected = true; break; }
            }
            if (!alreadyDetected) detectedDevices.add(device);

            for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
                if (slotAssignments.get(i) == null) {
                    // Inline the mutations that assignDeviceToSlot/setSlotEnabled
                    // would perform so both updates are atomic under one lock
                    // acquisition and saveAssignments() is called only once.
                    for (int j = 0; j < WinHandler.MAX_PLAYERS; j++) {
                        if (identifier.equals(slotAssignments.get(j))) {
                            slotAssignments.remove(j);
                        }
                    }
                    slotAssignments.put(i, identifier);
                    enabledSlots[i] = true;
                    assignedSlot = i;
                    break;
                }
            }
        }
        if (assignedSlot >= 0) {
            saveAssignments();
            android.util.Log.i("ControllerSlot", "autoAssign: '" + device.getName()
                    + "' -> slot=" + assignedSlot);
            return assignedSlot;
        }
        android.util.Log.w("ControllerSlot", "autoAssign: no slot available for '"
                + device.getName() + "'");
        return -1;
    }
}
