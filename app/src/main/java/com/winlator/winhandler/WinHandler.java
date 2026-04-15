package com.winlator.winhandler;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

// import com.winlator.XServerDisplayActivity;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControllerManager;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.TouchMouse;
import com.winlator.math.XForm;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class WinHandler {

    private static final String TAG = "WinHandler";
    private final ControllerManager controllerManager;
    public static final int MAX_PLAYERS = 4;
    private final MappedByteBuffer[] extraGamepadBuffers = new MappedByteBuffer[MAX_PLAYERS - 1];
    private final ExternalController[] extraControllers = new ExternalController[MAX_PLAYERS - 1];
    private MappedByteBuffer gamepadBuffer;
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;
    private final ArrayDeque<Runnable> actions;
    private ExternalController currentController;
    private byte dinputMapperType;
    private final List<Integer> gamepadClients;
    private boolean initReceived;
    private InetAddress localhost;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private PreferredInputApi preferredInputApi;
    private final ByteBuffer receiveData;
    private final DatagramPacket receivePacket;
    private boolean running;
    private final ByteBuffer sendData;
    private final DatagramPacket sendPacket;
    private DatagramSocket socket;
    private final ArrayList<Integer> xinputProcesses;
    private final XServer xServer;
    private final XServerView xServerView;

    private InputControlsView inputControlsView;
    private Thread rumblePollerThread;
    private final short[] lastLowFreqs = new short[MAX_PLAYERS];
    private final short[] lastHighFreqs = new short[MAX_PLAYERS];
    private final boolean[] isRumbling = new boolean[MAX_PLAYERS];
    private final int[] rumbleKeepaliveCtr = new int[MAX_PLAYERS];
    private static final int RUMBLE_KEEPALIVE_INTERVAL = 40;
    private static final int CONTROLLER_RUMBLE_MS = 1000;
    private static final int DEVICE_RUMBLE_MS = 60000;
    private boolean isShowingAssignDialog = false;
    private Context activity;
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();

    private String vibrationMode = "controller";
    private int vibrationIntensity = 100;

    private static final String USB_PERMISSION_ACTION = "app.gamenative.USB_PERMISSION";
    private final UsbDeviceConnection[] usbRumbleConn = new UsbDeviceConnection[MAX_PLAYERS];
    private final UsbEndpoint[] usbRumbleEndpoint = new UsbEndpoint[MAX_PLAYERS];
    private final boolean[] usbPermissionPending = new boolean[MAX_PLAYERS];

    public void setInputControlsView(InputControlsView view) {
        this.inputControlsView = view;
    }

    private static final Set<String> SUPPORTED_VIBRATION_MODES =
            new HashSet<>(Arrays.asList("off", "controller", "device", "both"));

    public void setVibrationMode(String mode) {
        String normalized = mode != null ? mode.trim().toLowerCase(java.util.Locale.ROOT) : "controller";
        if (!SUPPORTED_VIBRATION_MODES.contains(normalized)) {
            Log.w(TAG, "Unknown vibration mode '" + mode + "', falling back to 'controller'");
            normalized = "controller";
        }
        this.vibrationMode = normalized;
        Log.i(TAG, "Vibration mode set to: " + this.vibrationMode);
        if ("controller".equals(vibrationMode) || "both".equals(vibrationMode)) {
            requestUsbPermissionsForControllers();
        }
    }

    /**
     * Proactively requests USB Host API permission for any connected USB
     * gamepads whose standard Android vibrator APIs are unavailable.  Called
     * when the vibration mode is configured and when new controllers are
     * detected, so the user sees the permission dialog before gameplay
     * begins rather than mid-rumble.
     */
    public void requestUsbPermissionsForControllers() {
        if (activity == null) return;
        UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        for (int p = 0; p < MAX_PLAYERS; p++) {
            InputDevice device = resolveInputDeviceForPlayer(p);
            if (device == null) continue;

            boolean hasStandardVibrator = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = device.getVibratorManager();
                if (vm != null && vm.getVibratorIds().length > 0) hasStandardVibrator = true;
            }
            if (!hasStandardVibrator) {
                Vibrator v = device.getVibrator();
                if (v != null && v.hasVibrator()) hasStandardVibrator = true;
            }
            if (hasStandardVibrator) continue;

            int vid = device.getVendorId();
            int pid = device.getProductId();
            for (UsbDevice ud : usbManager.getDeviceList().values()) {
                if (ud.getVendorId() == vid && ud.getProductId() == pid) {
                    if (!usbManager.hasPermission(ud)) {
                        PendingIntent pi = PendingIntent.getBroadcast(activity, 0,
                                new Intent(USB_PERMISSION_ACTION), PendingIntent.FLAG_IMMUTABLE);
                        usbManager.requestPermission(ud, pi);
                        Log.i(TAG, "Proactively requesting USB permission for '" +
                                device.getName() + "' (no standard vibrator)");
                    }
                    break;
                }
            }
        }
    }

    public void setVibrationIntensity(int intensity) {
        this.vibrationIntensity = Math.max(0, Math.min(100, intensity));
        Log.i(TAG, "Vibration intensity set to: " + this.vibrationIntensity + "%");
    }

    public enum PreferredInputApi {
        AUTO,
        DINPUT,
        XINPUT,
        BOTH
    }

    public WinHandler(XServer xServer, XServerView xServerView) {
        ByteBuffer allocate = ByteBuffer.allocate(64);
        ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
        ByteBuffer order = allocate.order(byteOrder);
        this.sendData = order;
        ByteBuffer order2 = ByteBuffer.allocate(64).order(byteOrder);
        this.receiveData = order2;
        this.sendPacket = new DatagramPacket(order.array(), 64);
        this.receivePacket = new DatagramPacket(order2.array(), 64);
        this.actions = new ArrayDeque<>();
        this.initReceived = false;
        this.running = false;
        this.dinputMapperType = (byte) 1;
        this.preferredInputApi = PreferredInputApi.BOTH;
        this.gamepadClients = new CopyOnWriteArrayList();
        this.xinputProcesses = new ArrayList<>();
        this.xServer = xServer;
        this.xServerView = xServerView;
        this.controllerManager = ControllerManager.getInstance();
        this.activity = xServerView.getContext();
    }

    public void refreshControllerMappings() {
        Log.d(TAG, "Refreshing controller assignments from settings...");
        currentController = null;
        for (int i = 0; i < extraControllers.length; i++) {
            extraControllers[i] = null;
        }
        controllerManager.scanForDevices();
        InputDevice p1Device = controllerManager.getAssignedDeviceForSlot(0);
        if (p1Device != null) {
            currentController = ExternalController.getController(p1Device.getId());
            if (currentController != null) {
                currentController.setContext(activity);
                Log.i(TAG, "Initialized Player 1 with: " + p1Device.getName());
            }
        }
        // Initialize Extra Players (2, 3, 4)
        for (int i = 0; i < extraControllers.length; i++) {
            // Player 2 is slot 1, which corresponds to extraControllers[0]
            InputDevice extraDevice = controllerManager.getAssignedDeviceForSlot(i + 1);
            if (extraDevice != null) {
                extraControllers[i] = ExternalController.getController(extraDevice.getId());
                Log.i(TAG, "Initialized Player " + (i + 2) + " with: " + extraDevice.getName());
            }
        }
    }

    public MappedByteBuffer getBufferForSlot(int slot) {
        if (slot == 0) return gamepadBuffer;
        if (slot > 0 && slot <= extraGamepadBuffers.length) return extraGamepadBuffers[slot - 1];
        return null;
    }

    public ExternalController getControllerForSlot(int slot) {
        if (slot == 0) return currentController;
        if (slot > 0 && slot <= extraControllers.length) return extraControllers[slot - 1];
        return null;
    }

    public void setControllerForSlot(int slot, ExternalController controller) {
        if (slot < 0 || slot >= MAX_PLAYERS) return;
        ExternalController old = getControllerForSlot(slot);
        if (old != null && old != controller) {
            stopVibrationForPlayer(slot);
            lastLowFreqs[slot] = 0;
            lastHighFreqs[slot] = 0;
        }
        if (slot == 0) { currentController = controller; return; }
        if (slot > 0 && slot <= extraControllers.length) extraControllers[slot - 1] = controller;
    }

    private boolean sendPacket(int port) {
        try {
            int size = this.sendData.position();
            if (size == 0) {
                return false;
            }
            this.sendPacket.setAddress(this.localhost);
            this.sendPacket.setPort(port);
            this.socket.send(this.sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean sendPacket(int port, byte[] data) {
        try {
            DatagramPacket sendPacket = new DatagramPacket(data, data.length);
            sendPacket.setAddress(this.localhost);
            sendPacket.setPort(port);
            this.socket.send(sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        String command2 = command.trim();
        if (command2.isEmpty()) {
            return;
        }
        String[] cmdList = command2.split(" ", 2);
        final String filename = cmdList[0];
        final String parameters = cmdList.length > 1 ? cmdList[1] : "";
        addAction(() -> {
            byte[] filenameBytes = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();
            this.sendData.rewind();
            this.sendData.put(RequestCodes.EXEC);
            this.sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            this.sendData.putInt(filenameBytes.length);
            this.sendData.putInt(parametersBytes.length);
            this.sendData.put(filenameBytes);
            this.sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void killProcess(String processName) {
        killProcess(processName, 0);
    }

    public void killProcess(final String processName, final int pid) {
        addAction(() -> {
            this.sendData.rewind();
            this.sendData.put(RequestCodes.KILL_PROCESS);
            if (processName == null) {
                this.sendData.putInt(0);
            } else {
                byte[] bytes = processName.getBytes();
                int minLength = Math.min(bytes.length, 55);
                this.sendData.putInt(minLength);
                this.sendData.put(bytes, 0, minLength);
            }
            this.sendData.putInt(pid);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            OnGetProcessInfoListener onGetProcessInfoListener;
            this.sendData.rewind();
            this.sendData.put(RequestCodes.LIST_PROCESSES);
            this.sendData.putInt(0);
            if (!sendPacket(CLIENT_PORT) && (onGetProcessInfoListener = this.onGetProcessInfoListener) != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            this.sendData.rewind();
            this.sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            this.sendData.putInt(bytes.length + 9);
            this.sendData.putInt(0);
            this.sendData.putInt(affinityMask);
            this.sendData.put((byte)bytes.length);
            this.sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte)0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(final int flags, final int dx, final int dy, final int wheelDelta) {
        if (this.initReceived) {
            addAction(() -> {
                this.sendData.rewind();
                this.sendData.put(RequestCodes.MOUSE_EVENT);
                this.sendData.putInt(10);
                this.sendData.putInt(flags);
                this.sendData.putShort((short) dx);
                this.sendData.putShort((short) dy);
                this.sendData.putShort((short) wheelDelta);
                this.sendData.put((byte) ((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public void keyboardEvent(byte vkey, int flags) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
    }

    public void bringToFront(String processName) {
        bringToFront(processName, 0L);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            this.sendData.rewind();
            this.sendData.put(RequestCodes.BRING_TO_FRONT);
            byte[] bytes = processName.getBytes();
            int minLength = Math.min(bytes.length, 51);
            this.sendData.putInt(minLength);
            this.sendData.put(bytes, 0, minLength);
            this.sendData.putLong(handle);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setClipboardData(final String data) {
        addAction(() -> {
            this.sendData.rewind();
            byte[] bytes = data.getBytes();
            this.sendData.put((byte) 14);
            this.sendData.putInt(bytes.length);
            if (sendPacket(7946)) {
                sendPacket(7946, bytes);
            }
        });
    }

    private void addAction(Runnable action) {
        synchronized (this.actions) {
            this.actions.add(action);
            this.actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (this.actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (this.running) {
                synchronized (this.actions) {
                    while (this.initReceived && !this.actions.isEmpty()) {
                        this.actions.poll().run();
                    }
                    try {
                        this.actions.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        });
    }

    public void stop() {
        this.running = false;
        for (int p = 0; p < MAX_PLAYERS; p++) {
            stopVibrationForPlayer(p);
            closeUsbRumble(p);
        }
        DatagramSocket datagramSocket = this.socket;
        if (datagramSocket != null) {
            datagramSocket.close();
            this.socket = null;
        }
        synchronized (this.actions) {
            this.actions.notify();
        }
    }

    private void handleRequest(byte requestCode, final int port) throws IOException {
        boolean enabled = true;
        ExternalController externalController;
        switch (requestCode) {
            case RequestCodes.INIT:
                this.initReceived = true;
                synchronized (this.actions) {
                    this.actions.notify();
                }
                return;
            case RequestCodes.GET_PROCESS:
                if (this.onGetProcessInfoListener == null) {
                    return;
                }
                ByteBuffer byteBuffer = this.receiveData;
                byteBuffer.position(byteBuffer.position() + 4);
                int numProcesses = this.receiveData.getShort();
                int index = this.receiveData.getShort();
                int pid = this.receiveData.getInt();
                long memoryUsage = this.receiveData.getLong();
                int affinityMask = this.receiveData.getInt();
                boolean wow64Process = this.receiveData.get() == 1;
                byte[] bytes = new byte[32];
                this.receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);
                this.onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process));
                return;
            case RequestCodes.GET_GAMEPAD:
                boolean isXInput = this.receiveData.get() == 1;
                boolean notify = this.receiveData.get() == 1;
                final ControlsProfile profile = inputControlsView.getProfile();
                final boolean useVirtualGamepad = inputControlsView != null && profile != null && profile.isVirtualGamepad();
                int processId = this.receiveData.getInt();
                if (!useVirtualGamepad && ((externalController = this.currentController) == null || !externalController.isConnected())) {
                    this.currentController = ExternalController.getController(0);
                }
                boolean enabled2 = this.currentController != null || useVirtualGamepad;
                if (enabled2) {
                    switch (this.preferredInputApi) {
                        case DINPUT:
                            boolean hasXInputProcess = this.xinputProcesses.contains(Integer.valueOf(processId));
                            if (isXInput) {
                                if (!hasXInputProcess) {
                                    this.xinputProcesses.add(Integer.valueOf(processId));
                                    break;
                                }
                            } else if (hasXInputProcess) {
                                enabled = false;
                                break;
                            }
                            break;
                        case XINPUT:
                            if (isXInput) {
                                enabled = false;
                                break;
                            }
                            break;
                        case BOTH:
                            if (!isXInput) {
                                enabled = false;
                                break;
                            }
                            break;
                    }
                    if (notify) {
                        if (!this.gamepadClients.contains(Integer.valueOf(port))) {
                            this.gamepadClients.add(Integer.valueOf(port));
                        }
                    } else {
                        this.gamepadClients.remove(Integer.valueOf(port));
                    }
                    final boolean finalEnabled = enabled;
                    addAction(() -> {
                        this.sendData.rewind();
                        this.sendData.put((byte) RequestCodes.GET_GAMEPAD);
                        if (finalEnabled) {
                            this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : profile.id);
                            this.sendData.put(this.dinputMapperType);
                            String originalName = (useVirtualGamepad ? profile.getName() : currentController.getName());
                            byte[] originalBytes = originalName.getBytes();
                            final int MAX_NAME_LENGTH = 54;
                            byte[] bytesToWrite;
                            if (originalBytes.length > MAX_NAME_LENGTH) {
                                Log.w("WinHandler", "Controller name is too long ("+originalBytes.length+" bytes), truncating: "+originalName);
                                bytesToWrite = new byte[MAX_NAME_LENGTH];
                                System.arraycopy(originalBytes, 0, bytesToWrite, 0, MAX_NAME_LENGTH);
                            } else {
                                bytesToWrite = originalBytes;
                            }
                            sendData.putInt(bytesToWrite.length);
                            sendData.put(bytesToWrite);
                        } else {
                            this.sendData.putInt(0);
                            this.sendData.put((byte) 0);
                            this.sendData.putInt(0);
                        }
                        sendPacket(port);
                    });
                    return;
                }
                enabled = enabled2;
                if (!enabled) {
                }
                this.gamepadClients.remove(Integer.valueOf(port));
                final boolean finalEnabled2 = enabled;
                addAction(() -> {
                    this.sendData.rewind();
                    this.sendData.put((byte) 8);
                    if (finalEnabled2) {
                        this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : profile.id);
                        this.sendData.put(this.dinputMapperType);
                        byte[] bytes2 = (useVirtualGamepad ? profile.getName() : this.currentController.getName()).getBytes();
                        this.sendData.putInt(bytes2.length);
                        this.sendData.put(bytes2);
                    } else {
                        this.sendData.putInt(0);
                        this.sendData.put((byte) 0);
                        this.sendData.putInt(0);
                    }
                    sendPacket(port);
                });
                return;
            case RequestCodes.GET_GAMEPAD_STATE:
                final int gamepadId = this.receiveData.getInt();
                final ControlsProfile profile2 = inputControlsView.getProfile();
                final boolean useVirtualGamepad2 = inputControlsView != null && profile2 != null && profile2.isVirtualGamepad();
                ExternalController externalController2 = this.currentController;
                final boolean enabled3 = externalController2 != null || useVirtualGamepad2;
                if (externalController2 != null && externalController2.getDeviceId() != gamepadId) {
                    this.currentController = null;
                }
                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    this.sendData.put((byte)(enabled3 ? 1 : 0));
                    if (enabled3) {
                        this.sendData.putInt(gamepadId);
                        if (useVirtualGamepad2) {
                            inputControlsView.getProfile().getGamepadState().writeTo(this.sendData);
                        } else {
                            this.currentController.state.writeTo(this.sendData);
                        }
                    }
                    sendPacket(port);
                });
                return;
            case RequestCodes.RELEASE_GAMEPAD:
                this.currentController = null;
                this.gamepadClients.clear();
                this.xinputProcesses.clear();
                return;
            case RequestCodes.CURSOR_POS_FEEDBACK:
                short x = this.receiveData.getShort();
                short y = this.receiveData.getShort();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                xServerView.requestRender();
                return;
            default:
                return;
        }
    }

    public void start() {
        try {
            this.localhost = InetAddress.getLocalHost();
            // Player 1 (currentController) gets the original non-numbered file
            String p1_mem_path = "/data/data/app.gamenative/files/imagefs/tmp/gamepad.mem";
            File p1_memFile = new File(p1_mem_path);
            p1_memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(p1_memFile, "rw")) {
                raf.setLength(64);
                gamepadBuffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                gamepadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                Log.i(TAG, "Successfully created and mapped gamepad file for Player 1");
            }
            for (int i = 0; i < extraGamepadBuffers.length; i++) {
                String extra_mem_path = "/data/data/app.gamenative/files/imagefs/tmp/gamepad" + (i + 1) + ".mem";
                File extra_memFile = new File(extra_mem_path);
                try (RandomAccessFile raf = new RandomAccessFile(extra_memFile, "rw")) {
                    raf.setLength(64);
                    extraGamepadBuffers[i] = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                    extraGamepadBuffers[i].order(ByteOrder.LITTLE_ENDIAN);
                    Log.i(TAG, "Successfully created and mapped gamepad file for Player " + (i + 2));
                }
            }
        } catch (IOException e) {
            Log.e("EVSHIM_HOST", "FATAL: Failed to create memory-mapped file(s).", e);
            try {
                this.localhost = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException e2) {
            }
        }
        this.running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DatagramSocket datagramSocket = new DatagramSocket((SocketAddress) null);
                this.socket = datagramSocket;
                datagramSocket.setReuseAddress(true);
                this.socket.bind(new InetSocketAddress((InetAddress) null, 7947));
                while (this.running) {
                    this.socket.receive(this.receivePacket);
                    synchronized (this.actions) {
                        this.receiveData.rewind();
                        byte requestCode = this.receiveData.get();
                        handleRequest(requestCode, this.receivePacket.getPort());
                    }
                }
            } catch (IOException e) {
            }
        });

        startRumblePoller();
        running = true;
        startSendThread();
    }

    private void startRumblePoller() {
        // poller skips case where controller is null and we
        // do NOT vibrate phone as of now to prevent issues with docked users.
        // TODO: add phone vibration option in upcoming ux when no controller device connected
        rumblePollerThread = new Thread(() -> {
            while (running) {
                for (int p = 0; p < MAX_PLAYERS; p++) {
                    try {
                        MappedByteBuffer buf = getBufferForSlot(p);
                        if (buf == null) continue;
                        short lowFreq = buf.getShort(32);
                        short highFreq = buf.getShort(34);
                        boolean changed = lowFreq != lastLowFreqs[p] || highFreq != lastHighFreqs[p];
                        if (changed) {
                            lastLowFreqs[p] = lowFreq;
                            lastHighFreqs[p] = highFreq;
                            rumbleKeepaliveCtr[p] = 0;
                            if (lowFreq == 0 && highFreq == 0) {
                                stopVibrationForPlayer(p);
                            } else {
                                startVibrationForPlayer(p, lowFreq, highFreq);
                            }
                        } else if (isRumbling[p]) {
                            rumbleKeepaliveCtr[p]++;
                            if (rumbleKeepaliveCtr[p] >= RUMBLE_KEEPALIVE_INTERVAL) {
                                rumbleKeepaliveCtr[p] = 0;
                                startVibrationForPlayer(p, lowFreq, highFreq, true);
                            }
                        }
                    } catch (Exception e) {
                        // Buffer may be unmapped; continue polling
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        rumblePollerThread.start();
    }

    private boolean controllerVibrationDiagLogged = false;

    private int scaleAmplitude(short rawFreq, int intensityPercent) {
        int unsigned = rawFreq & 0xFFFF;
        int base = (unsigned >> 8) & 0xFF;
        return Math.min(255, Math.max(0, (base * intensityPercent) / 100));
    }

    @TargetApi(31)
    private void logVibratorManagerDiag(InputDevice device, VibratorManager vm) {
        if (controllerVibrationDiagLogged) return;
        controllerVibrationDiagLogged = true;
        int[] ids = vm.getVibratorIds();
        StringBuilder sb = new StringBuilder();
        sb.append("VibratorManager for '").append(device.getName())
          .append("' (id=").append(device.getId())
          .append("): ").append(ids.length).append(" vibrator(s)");
        for (int id : ids) {
            Vibrator vib = vm.getVibrator(id);
            sb.append(" [id=").append(id)
              .append(" hasVibrator=").append(vib.hasVibrator())
              .append(" amplitudeCtrl=").append(vib.hasAmplitudeControl())
              .append("]");
        }
        Vibrator legacyVib = device.getVibrator();
        sb.append(" | legacy getVibrator(): hasVibrator=")
          .append(legacyVib != null && legacyVib.hasVibrator());
        if (legacyVib != null) {
            sb.append(" amplitudeCtrl=").append(legacyVib.hasAmplitudeControl());
        }
        Log.i(TAG, sb.toString());
    }

    private VibrationAttributes buildVibrationAttrs() {
        VibrationAttributes.Builder attrs = new VibrationAttributes.Builder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            attrs.setUsage(VibrationAttributes.USAGE_MEDIA);
        }
        return attrs.build();
    }

    private AudioAttributes buildAudioAttrs() {
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .build();
    }

    private void vibrateSingle(Vibrator vibrator, int amplitude, int durationMs) {
        if (amplitude <= 0) { vibrator.cancel(); return; }
        int amp = Math.min(255, amplitude);

        if (vibrator.hasAmplitudeControl()) {
            VibrationEffect effect = VibrationEffect.createOneShot(durationMs, amp);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, buildVibrationAttrs());
            } else {
                vibrator.vibrate(effect, buildAudioAttrs());
            }
        } else {
            VibrationEffect effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, buildVibrationAttrs());
            } else {
                vibrator.vibrate(effect, buildAudioAttrs());
            }
        }
    }

    @TargetApi(31)
    private boolean rumbleViaVibratorManager(VibratorManager vm, short lowFreq, short highFreq) {
        int[] ids = vm.getVibratorIds();
        if (ids.length == 0) return false;

        int highAmp = scaleAmplitude(highFreq, vibrationIntensity);
        int lowAmp = scaleAmplitude(lowFreq, vibrationIntensity);
        if (lowAmp == 0 && highAmp == 0) { vm.cancel(); return true; }

        CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
        boolean anyAdded = false;

        if (ids.length >= 2) {
            if (lowAmp > 0) {
                int a = vm.getVibrator(ids[0]).hasAmplitudeControl() ? lowAmp : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(ids[0], VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
            if (highAmp > 0) {
                int a = vm.getVibrator(ids[1]).hasAmplitudeControl() ? highAmp : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(ids[1], VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
        } else {
            int blended = Math.min(255, (int)(lowAmp * 0.80 + highAmp * 0.33));
            if (blended > 0) {
                int a = vm.getVibrator(ids[0]).hasAmplitudeControl() ? blended : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(ids[0], VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
        }

        if (!anyAdded) { vm.cancel(); return true; }
        vm.vibrate(combo.combine(), buildVibrationAttrs());
        return true;
    }

    /**
     * Resolves the physical InputDevice for a player slot.
     * Checks ControllerManager slot assignment first, then falls back to
     * the slot's ExternalController, then to first detected gamepad (P0 only).
     */
    private InputDevice resolveInputDeviceForPlayer(int player) {
        InputDevice device = controllerManager.getAssignedDeviceForSlot(player);
        if (device != null) return device;

        ExternalController ctrl = getControllerForSlot(player);
        if (ctrl != null) {
            device = InputDevice.getDevice(ctrl.getDeviceId());
            if (device != null) return device;
        }

        if (player == 0) {
            List<InputDevice> detected = controllerManager.getDetectedDevices();
            if (!detected.isEmpty()) {
                return detected.get(0);
            }
        }
        return null;
    }

    private boolean vibrateController(int player, short lowFreq, short highFreq) {
        InputDevice device = resolveInputDeviceForPlayer(player);
        if (device == null) {
            Log.w(TAG, "Rumble P" + player + ": no physical controller found");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = device.getVibratorManager();
                logVibratorManagerDiag(device, vm);
                if (rumbleViaVibratorManager(vm, lowFreq, highFreq)) {
                    return true;
                }
            }

            Vibrator v = device.getVibrator();
            if (v != null && v.hasVibrator()) {
                int lowMSB = scaleAmplitude(lowFreq, vibrationIntensity);
                int highMSB = scaleAmplitude(highFreq, vibrationIntensity);
                int blended = Math.min(255, (int)(lowMSB * 0.80 + highMSB * 0.33));
                vibrateSingle(v, blended, CONTROLLER_RUMBLE_MS);
                return true;
            }
            Log.w(TAG, "Rumble P" + player + ": standard APIs failed on '" + device.getName() + "', trying USB");
        } catch (Exception e) {
            Log.e(TAG, "Rumble P" + player + ": exception vibrating controller", e);
        }

        return vibrateViaUsb(player, device, lowFreq, highFreq);
    }

    private boolean vibrateViaUsb(int player, InputDevice device, short lowFreq, short highFreq) {
        try {
            if (usbRumbleConn[player] != null && usbRumbleEndpoint[player] != null) {
                return sendUsbRumble(player, lowFreq, highFreq);
            }

            UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
            if (usbManager == null) return false;

            int vid = device.getVendorId();
            int pid = device.getProductId();
            UsbDevice usbDevice = null;
            for (UsbDevice ud : usbManager.getDeviceList().values()) {
                if (ud.getVendorId() == vid && ud.getProductId() == pid) {
                    usbDevice = ud;
                    break;
                }
            }
            if (usbDevice == null) return false;

            if (!usbManager.hasPermission(usbDevice)) {
                if (!usbPermissionPending[player]) {
                    usbPermissionPending[player] = true;
                    PendingIntent pi = PendingIntent.getBroadcast(activity, 0,
                            new Intent(USB_PERMISSION_ACTION), PendingIntent.FLAG_IMMUTABLE);
                    usbManager.requestPermission(usbDevice, pi);
                    Log.i(TAG, "USB rumble: requesting permission for " + usbDevice.getDeviceName());
                }
                return false;
            }

            UsbDeviceConnection conn = usbManager.openDevice(usbDevice);
            if (conn == null) {
                Log.w(TAG, "USB rumble: failed to open " + usbDevice.getDeviceName());
                return false;
            }

            UsbInterface iface = null;
            UsbEndpoint outEp = null;
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface ui = usbDevice.getInterface(i);
                for (int j = 0; j < ui.getEndpointCount(); j++) {
                    UsbEndpoint ep = ui.getEndpoint(j);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                        ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        iface = ui;
                        outEp = ep;
                        break;
                    }
                }
                if (outEp != null) break;
            }

            if (iface == null || outEp == null) {
                conn.close();
                Log.w(TAG, "USB rumble: no interrupt OUT endpoint on " + usbDevice.getDeviceName());
                return false;
            }

            if (!conn.claimInterface(iface, true)) {
                conn.close();
                Log.w(TAG, "USB rumble: failed to claim interface");
                return false;
            }

            usbRumbleConn[player] = conn;
            usbRumbleEndpoint[player] = outEp;
            Log.i(TAG, "USB rumble: connection established for P" + player +
                    " on " + usbDevice.getDeviceName() +
                    " iface=" + iface.getId() + " ep=" + outEp.getAddress());

            return sendUsbRumble(player, lowFreq, highFreq);
        } catch (Exception e) {
            Log.e(TAG, "USB rumble P" + player + ": exception", e);
            return false;
        }
    }

    private boolean sendUsbRumble(int player, short lowFreq, short highFreq) {
        UsbDeviceConnection conn = usbRumbleConn[player];
        UsbEndpoint ep = usbRumbleEndpoint[player];
        if (conn == null || ep == null) return false;

        int leftMotor = ((lowFreq & 0xFFFF) * vibrationIntensity / 100) >> 8;
        int rightMotor = ((highFreq & 0xFFFF) * vibrationIntensity / 100) >> 8;

        byte[] report = new byte[]{
            0x00, 0x08, 0x00,
            (byte) leftMotor, (byte) rightMotor,
            0x00, 0x00, 0x00
        };

        int result = conn.bulkTransfer(ep, report, report.length, 100);
        if (result < 0) {
            Log.w(TAG, "USB rumble P" + player + ": bulkTransfer failed (" + result + "), releasing");
            closeUsbRumble(player);
            return false;
        }
        return true;
    }

    private void closeUsbRumble(int player) {
        if (usbRumbleConn[player] != null) {
            try { usbRumbleConn[player].close(); } catch (Exception ignored) {}
            usbRumbleConn[player] = null;
        }
        usbRumbleEndpoint[player] = null;
        usbPermissionPending[player] = false;
    }

    private void vibrateDevice(short lowFreq, short highFreq) {
        try {
            Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phoneVibrator == null || !phoneVibrator.hasVibrator()) return;

            int lowMSB = scaleAmplitude(lowFreq, vibrationIntensity);
            int highMSB = scaleAmplitude(highFreq, vibrationIntensity);
            int rawAmplitude = Math.min(255, (int)(lowMSB * 0.80 + highMSB * 0.33));

            if (rawAmplitude == 0) { phoneVibrator.cancel(); return; }

            float curved = (float) Math.pow((float) rawAmplitude / 255f, 0.6f);
            int phoneAmp = Math.round(curved * 255);
            if (phoneAmp > 255) phoneAmp = 255;
            if (phoneAmp <= 0) { phoneVibrator.cancel(); return; }

            vibrateSingle(phoneVibrator, phoneAmp, DEVICE_RUMBLE_MS);
        } catch (Exception e) {
            Log.e(TAG, "Rumble: exception vibrating device", e);
        }
    }

    private void startVibrationForPlayer(int player, short lowFreq, short highFreq) {
        startVibrationForPlayer(player, lowFreq, highFreq, false);
    }

    /**
     * @param keepalive true when called from the keepalive path — refreshes
     *                  controller rumble but skips re-triggering the device
     *                  vibrator (which uses a long one-shot that doesn't need
     *                  periodic resets).
     */
    private void startVibrationForPlayer(int player, short lowFreq, short highFreq, boolean keepalive) {
        if ("off".equals(vibrationMode)) return;

        Log.d(TAG, "Rumble P" + player + ": low=" + (lowFreq & 0xFFFF) + " high=" + (highFreq & 0xFFFF)
                + " mode=" + vibrationMode + " intensity=" + vibrationIntensity);

        isRumbling[player] = true;

        if ("controller".equals(vibrationMode)) {
            vibrateController(player, lowFreq, highFreq);
        } else if ("device".equals(vibrationMode)) {
            if (!keepalive) vibrateDevice(lowFreq, highFreq);
        } else if ("both".equals(vibrationMode)) {
            vibrateController(player, lowFreq, highFreq);
            if (!keepalive) vibrateDevice(lowFreq, highFreq);
        }
    }

    private void stopVibrationForPlayer(int player) {
        if (!isRumbling[player]) return;

        // Always cancel both controller and device vibration unconditionally.
        // The mode may have changed since vibration started, so we must ensure
        // nothing is left buzzing from the previous mode.
        try {
            InputDevice device = resolveInputDeviceForPlayer(player);
            if (device != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager vm = device.getVibratorManager();
                    if (vm.getVibratorIds().length > 0) {
                        vm.cancel();
                    }
                }
                Vibrator vibrator = device.getVibrator();
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.cancel();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling controller vibration", e);
        }
        sendUsbRumble(player, (short) 0, (short) 0);

        try {
            Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phoneVibrator != null) {
                phoneVibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling device vibration", e);
        }

        isRumbling[player] = false;
    }

    public void sendGamepadState() {
        if (!this.initReceived || this.gamepadClients.isEmpty()) {
            return;
        }
        final ControlsProfile profile = inputControlsView.getProfile();
        final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
        final boolean enabled = this.currentController != null || useVirtualGamepad;
        Iterator<Integer> it = this.gamepadClients.iterator();
        while (it.hasNext()) {
            final int port = it.next().intValue();
            addAction(() -> {
                this.sendData.rewind();
                sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                sendData.put((byte)(enabled ? 1 : 0));
                if (enabled) {
                    this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : inputControlsView.getProfile().id);
                    if (useVirtualGamepad) {
                        inputControlsView.getProfile().getGamepadState().writeTo(sendData);
                    } else {
                        this.currentController.state.writeTo(this.sendData);
                    }
                }
                sendPacket(port);
            });
        }
    }

    /**
     * Resolves (or adopts) an ExternalController for the given device into the correct player slot.
     * Returns the slot index (0-3) or -1 if the device is not a game controller.
     */
    private int resolveControllerSlot(int deviceId) {
        int slot = controllerManager.autoAssignDevice(deviceId);
        if (slot < 0) return -1;

        ExternalController controller = getControllerForSlot(slot);
        if (controller == null || controller.getDeviceId() != deviceId) {
            ExternalController adopted = null;
            if (inputControlsView != null) {
                ControlsProfile profile = inputControlsView.getProfile();
                if (profile != null) {
                    adopted = profile.getController(deviceId);
                }
            }
            if (adopted == null) {
                adopted = ExternalController.getController(deviceId);
            }
            if (adopted != null) {
                setControllerForSlot(slot, adopted);
                Timber.d("WinHandler: adopted controller %s(#%d) to slot %d", adopted.getName(), adopted.getDeviceId(), slot);
            }
        }
        return slot;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!ExternalController.isJoystickDevice(event)) return false;

        int slot = resolveControllerSlot(event.getDeviceId());
        if (slot < 0) return false;

        ExternalController controller = getControllerForSlot(slot);
        if (controller != null && controller.updateStateFromMotionEvent(event)) {
            MappedByteBuffer buffer = getBufferForSlot(slot);
            if (buffer != null) sendMemoryFileState(controller, buffer);
            if (slot == 0) sendGamepadState();
            return true;
        }
        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || !ExternalController.isGameController(device) || event.getRepeatCount() != 0) {
            return false;
        }

        int slot = resolveControllerSlot(event.getDeviceId());
        if (slot < 0) return false;

        ExternalController controller = getControllerForSlot(slot);
        if (controller == null) return false;

        boolean handled = false;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            handled = controller.updateStateFromKeyEvent(event);
        }
        MappedByteBuffer buffer = getBufferForSlot(slot);
        if (buffer != null) sendMemoryFileState(controller, buffer);
        if (handled && slot == 0) sendGamepadState();
        return handled;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public void setPreferredInputApi(PreferredInputApi preferredInputApi) {
        this.preferredInputApi = preferredInputApi;
    }

    public ExternalController getCurrentController() {
        return this.currentController;
    }


    public void sendMemoryFileState(ExternalController controller, MappedByteBuffer buffer) {
        if (buffer == null || controller == null) {
            return;
        }
        GamepadState state = controller.state;
        buffer.clear();

        // --- Sticks and Buttons are perfect. No changes here. ---
        buffer.putShort((short)(state.thumbLX * 32767));
        buffer.putShort((short)(state.thumbLY * 32767));
        buffer.putShort((short)(state.thumbRX * 32767));
        buffer.putShort((short)(state.thumbRY * 32767));
        // Clamp the raw value first – some firmwares report 1.00–1.02 at the top end
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;  // 0 → -32 767, 1 → 32 767
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        buffer.putShort((short)lAxis);
        buffer.putShort((short)rAxis);
        // --- Buttons and D-Pad are perfect. No changes here. ---
        byte[] sdlButtons = new byte[15];
        sdlButtons[0] = state.isPressed(0) ? (byte)1 : (byte)0;  // A
        sdlButtons[1] = state.isPressed(1) ? (byte)1 : (byte)0;  // B
        sdlButtons[2] = state.isPressed(2) ? (byte)1 : (byte)0;  // X
        sdlButtons[3] = state.isPressed(3) ? (byte)1 : (byte)0;  // Y
        sdlButtons[9] = state.isPressed(4) ? (byte)1 : (byte)0;  // Left Bumper
        sdlButtons[10] = state.isPressed(5) ? (byte)1 : (byte)0; // Right Bumper
        sdlButtons[4] = state.isPressed(6) ? (byte)1 : (byte)0;  // Select/Back
        sdlButtons[6] = state.isPressed(7) ? (byte)1 : (byte)0;  // Start
        sdlButtons[7] = state.isPressed(8) ? (byte)1 : (byte)0;  // Left Stick
        sdlButtons[8] = state.isPressed(9) ? (byte)1 : (byte)0;  // Right Stick
        sdlButtons[11] = state.dpad[0] ? (byte)1 : (byte)0;      // DPAD_UP
        sdlButtons[12] = state.dpad[2] ? (byte)1 : (byte)0;      // DPAD_DOWN
        sdlButtons[13] = state.dpad[3] ? (byte)1 : (byte)0;      // DPAD_LEFT
        sdlButtons[14] = state.dpad[1] ? (byte)1 : (byte)0;      // DPAD_RIGHT
        buffer.put(sdlButtons);
        buffer.put((byte)0); // Ignored HAT value
    }

    public void sendVirtualGamepadState(GamepadState state) {
        if (gamepadBuffer == null || state == null) {
            return;
        }
        gamepadBuffer.clear();

        gamepadBuffer.putShort((short)(state.thumbLX * 32767));
        gamepadBuffer.putShort((short)(state.thumbLY * 32767));
        gamepadBuffer.putShort((short)(state.thumbRX * 32767));
        gamepadBuffer.putShort((short)(state.thumbRY * 32767));

        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        gamepadBuffer.putShort((short)lAxis);
        gamepadBuffer.putShort((short)rAxis);

        // Buttons & D-Pad
        byte[] sdlButtons = new byte[15];
        sdlButtons[0] = state.isPressed(0) ? (byte)1 : (byte)0;  // A
        sdlButtons[1] = state.isPressed(1) ? (byte)1 : (byte)0;  // B
        sdlButtons[2] = state.isPressed(2) ? (byte)1 : (byte)0;  // X
        sdlButtons[3] = state.isPressed(3) ? (byte)1 : (byte)0;  // Y
        sdlButtons[9] = state.isPressed(4) ? (byte)1 : (byte)0;  // Left Bumper
        sdlButtons[10] = state.isPressed(5) ? (byte)1 : (byte)0; // Right Bumper
        sdlButtons[4] = state.isPressed(6) ? (byte)1 : (byte)0;  // Select/Back
        sdlButtons[6] = state.isPressed(7) ? (byte)1 : (byte)0;  // Start
        sdlButtons[7] = state.isPressed(8) ? (byte)1 : (byte)0;  // Left Stick
        sdlButtons[8] = state.isPressed(9) ? (byte)1 : (byte)0;  // Right Stick
        sdlButtons[11] = state.dpad[0] ? (byte)1 : (byte)0;      // DPAD_UP
        sdlButtons[12] = state.dpad[2] ? (byte)1 : (byte)0;      // DPAD_DOWN
        sdlButtons[13] = state.dpad[3] ? (byte)1 : (byte)0;      // DPAD_LEFT
        sdlButtons[14] = state.dpad[1] ? (byte)1 : (byte)0;      // DPAD_RIGHT
        gamepadBuffer.put(sdlButtons);
        gamepadBuffer.put((byte)0); // Ignored HAT value
    }

    private void initializeAssignedControllers() {
        Log.d(TAG, "Initializing controller assignments from saved settings...");
        for (int i = 0; i < MAX_PLAYERS; i++) {
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            if (device != null) {
                ExternalController controller = ExternalController.getController(device.getId());
                if (i == 0) {
                    currentController = controller;
                    Log.d(TAG, "Assigned '" + device.getName() + "' to Player 1 at startup.");
                } else {
                    // Remember that extraControllers is 0-indexed for players 2-4
                    // So Player 2 (slot index 1) goes into extraControllers[0]
                    extraControllers[i - 1] = controller;
                    Log.d(TAG, "Assigned '" + device.getName() + "' to Player " + (i + 1) + " at startup.");
                }
            }
        }
        // This ensures P1-specific settings (like trigger type) are applied from preferences.
        refreshControllerMappings();
    }
    public void clearIgnoredDevices() {
        ignoredDeviceIds.clear();
    }
}
