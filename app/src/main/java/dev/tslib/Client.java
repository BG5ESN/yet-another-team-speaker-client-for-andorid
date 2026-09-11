package dev.tslib;

/**
 * A TeamSpeak 3 client connection.
 *
 * <p>The client is <strong>not thread-safe</strong>. All method calls must
 * happen from the same thread that created the instance.
 *
 * <pre>{@code
 * try (Client client = new Client("localhost:9987", identity, "JavaBot")) {
 *     client.waitConnected();
 *     client.sendServerMessage("Hello!");
 * }
 * }</pre>
 */
public class Client implements AutoCloseable {
    private long nativePtr;

    /**
     * Connect to a TeamSpeak server.
     *
     * @param address  server address (host:port)
     * @param identity the identity to authenticate with
     * @param nickname display name on the server
     * @throws TsLibException on connection failure
     */
    public Client(String address, Identity identity, String nickname) {
        this(address, identity, nickname, null, null);
    }

    /**
     * Connect to a TeamSpeak server with optional password and channel.
     *
     * @param address  server address (host:port)
     * @param identity the identity to authenticate with
     * @param nickname display name on the server
     * @param password server password (may be {@code null})
     * @param channel  default channel to join (may be {@code null})
     * @throws TsLibException on connection failure
     */
    public Client(String address, Identity identity, String nickname,
                  String password, String channel) {
        this.nativePtr = nativeCreate(address, identity.getNativePtr(),
                                       nickname, password, channel);
        if (this.nativePtr == 0) {
            throw new TsLibException("Failed to connect to " + address);
        }
    }

    /**
     * Wait for the connection to be fully established.
     * Blocks until initial state synchronization is complete.
     *
     * @throws TsLibException on error
     */
    public void waitConnected() {
        checkNotClosed();
        nativeWaitConnected(nativePtr);
    }

    /**
     * Process pending events from the server.
     *
     * @return array of events (may be empty)
     * @throws TsLibException on error
     */
    public Event[] processEvents() {
        checkNotClosed();
        return nativeProcessEvents(nativePtr);
    }

    /**
     * Disconnect from the server.
     *
     * @throws TsLibException on error
     */
    public void disconnect() {
        checkNotClosed();
        nativeDisconnect(nativePtr);
    }

    /**
     * Whether the client is currently connected.
     */
    public boolean isConnected() {
        checkNotClosed();
        return nativeIsConnected(nativePtr);
    }

    /**
     * Get the current connection state.
     *
     * @return one of the {@link ConnectionState} constants
     */
    public int getState() {
        checkNotClosed();
        return nativeGetState(nativePtr);
    }

    /**
     * Get our client ID on the server.
     *
     * @return the client ID, or {@code null} if not yet assigned
     */
    public Integer getClientId() {
        checkNotClosed();
        return nativeGetClientId(nativePtr);
    }

    /**
     * Get our current channel ID.
     *
     * @return the channel ID, or {@code null} if not in a channel
     */
    public Long getChannelId() {
        checkNotClosed();
        return nativeGetChannelId(nativePtr);
    }

    /**
     * Get all channels on the server.
     *
     * @return array of channels
     */
    public Channel[] getChannels() {
        checkNotClosed();
        return nativeGetChannels(nativePtr);
    }

    /**
     * Get all connected users.
     *
     * @return array of users
     */
    public User[] getUsers() {
        checkNotClosed();
        return nativeGetUsers(nativePtr);
    }

    /**
     * Get a specific channel by ID.
     *
     * @param id channel ID
     * @return the channel, or {@code null} if not found
     */
    public Channel getChannel(long id) {
        checkNotClosed();
        return nativeGetChannel(nativePtr, id);
    }

    /**
     * Get a specific user by ID.
     *
     * @param id user (client) ID
     * @return the user, or {@code null} if not found
     */
    public User getUser(int id) {
        checkNotClosed();
        return nativeGetUser(nativePtr, id);
    }

    /**
     * Get server information.
     *
     * @return server info snapshot
     */
    public ServerInfo getServerInfo() {
        checkNotClosed();
        return nativeGetServerInfo(nativePtr);
    }

    /**
     * Send a message to the entire server.
     *
     * @param msg message text (may contain BBCode)
     * @throws TsLibException on error
     */
    public void sendServerMessage(String msg) {
        checkNotClosed();
        nativeSendServerMessage(nativePtr, msg);
    }

    /**
     * Send a message to the current channel.
     *
     * @param msg message text
     * @throws TsLibException on error
     */
    public void sendChannelMessage(String msg) {
        checkNotClosed();
        nativeSendChannelMessage(nativePtr, msg);
    }

    /**
     * Send a private message to a user.
     *
     * @param userId target user ID
     * @param msg    message text
     * @throws TsLibException on error
     */
    public void sendPrivateMessage(int userId, String msg) {
        checkNotClosed();
        nativeSendPrivateMessage(nativePtr, userId, msg);
    }

    /**
     * Move to a different channel.
     *
     * @param channelId target channel ID
     * @throws TsLibException on error
     */
    public void moveToChannel(long channelId) {
        checkNotClosed();
        nativeMoveToChannel(nativePtr, channelId);
    }

    /**
     * Re-synchronize the local state from the server.
     *
     * @throws TsLibException on error
     */
    public void syncState() {
        checkNotClosed();
        nativeSyncState(nativePtr);
    }

    /**
     * Notify the server of our input muted state.
     *
     * @param muted true if microphone is muted
     * @throws TsLibException on error
     */
    public void setInputMuted(boolean muted) {
        checkNotClosed();
        nativeSetInputMuted(nativePtr, muted);
    }

    /**
     * Send encoded audio data to the server.
     *
     * @param data  encoded audio data (e.g. Opus)
     * @param codec audio codec ID (see {@link AudioCodec} constants: 4 = Opus Voice, 5 = Opus Music)
     * @throws TsLibException on error
     */
    public void sendAudio(byte[] data, int codec) {
        checkNotClosed();
        nativeSendAudio(nativePtr, data, codec);
    }

    /**
     * Initiate a file download from the server.
     * The result will be delivered as a {@code file_downloaded} or
     * {@code file_transfer_failed} event via {@link #processEvents()}.
     *
     * @param channelId channel containing the file (0 for icons)
     * @param path      virtual path on the server (e.g. "/icon_12345")
     * @throws TsLibException on error
     */
    public void downloadFile(long channelId, String path) {
        checkNotClosed();
        nativeDownloadFile(nativePtr, channelId, path);
    }

    /**
     * Initiate a file upload to the server.
     * The result will be delivered as a {@code file_uploaded} or
     * {@code file_transfer_failed} event via {@link #processEvents()}.
     *
     * @param channelId channel to upload to
     * @param path      virtual path on the server (e.g. "/myfile.png")
     * @param data      file content bytes
     * @param overwrite whether to overwrite existing file
     * @throws TsLibException on error
     */
    public void uploadFile(long channelId, String path, byte[] data, boolean overwrite) {
        checkNotClosed();
        nativeUploadFile(nativePtr, channelId, path, data, overwrite);
    }

    /**
     * Request the file list for a channel directory.
     * The result will be delivered as a {@code file_list_received} event.
     */
    public void listFiles(long channelId, String path) {
        checkNotClosed();
        nativeListFiles(nativePtr, channelId, path);
    }

    /**
     * Query effective permissions for the current user in a channel.
     * The result will be delivered as a {@code channel_permissions_updated} event.
     */
    public void queryChannelPermissions(long channelId) {
        checkNotClosed();
        nativeQueryChannelPermissions(nativePtr, channelId);
    }

    /**
     * Delete a file on the server.
     */
    public void deleteFile(long channelId, String name) {
        checkNotClosed();
        nativeDeleteFile(nativePtr, channelId, name);
    }

    /**
     * Rename a file on the server.
     */
    public void renameFile(long channelId, String oldName, String newName) {
        checkNotClosed();
        nativeRenameFile(nativePtr, channelId, oldName, newName);
    }

    /**
     * Create a directory on the server.
     */
    public void createDirectory(long channelId, String dirname) {
        checkNotClosed();
        nativeCreateDirectory(nativePtr, channelId, dirname);
    }

    @Override
    public void close() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    private void checkNotClosed() {
        if (nativePtr == 0) {
            throw new IllegalStateException("Client has been closed");
        }
    }

    // Native methods
    /**
     * 把 Application context 交给 Rust 侧的 ndk-context（App 启动时调用一次）。
     * 新版底层（hickory-resolver）在 Android 上要靠它读系统 DNS，
     * 未初始化时解析地址会直接 panic 导致闪退。
     */
    public static native void nativeSetAndroidContext(Object context);

    private static native long nativeCreate(String address, long identityPtr,
                                             String nickname, String password,
                                             String channel);
    private static native void nativeDestroy(long ptr);
    private static native void nativeWaitConnected(long ptr);
    private static native Event[] nativeProcessEvents(long ptr);
    private static native void nativeDisconnect(long ptr);
    private static native boolean nativeIsConnected(long ptr);
    private static native int nativeGetState(long ptr);
    private static native Integer nativeGetClientId(long ptr);
    private static native Long nativeGetChannelId(long ptr);
    private static native Channel[] nativeGetChannels(long ptr);
    private static native User[] nativeGetUsers(long ptr);
    private static native Channel nativeGetChannel(long ptr, long id);
    private static native User nativeGetUser(long ptr, int id);
    private static native ServerInfo nativeGetServerInfo(long ptr);
    private static native void nativeSendServerMessage(long ptr, String msg);
    private static native void nativeSendChannelMessage(long ptr, String msg);
    private static native void nativeSendPrivateMessage(long ptr, int userId, String msg);
    private static native void nativeMoveToChannel(long ptr, long channelId);
    private static native void nativeSyncState(long ptr);
    private static native void nativeSetInputMuted(long ptr, boolean muted);
    private static native void nativeSendAudio(long ptr, byte[] data, int codec);
    private static native void nativeDownloadFile(long ptr, long channelId, String path);
    private static native void nativeUploadFile(long ptr, long channelId, String path, byte[] data, boolean overwrite);
    private static native void nativeListFiles(long ptr, long channelId, String path);
    private static native void nativeQueryChannelPermissions(long ptr, long channelId);
    private static native void nativeDeleteFile(long ptr, long channelId, String name);
    private static native void nativeRenameFile(long ptr, long channelId, String oldName, String newName);
    private static native void nativeCreateDirectory(long ptr, long channelId, String dirname);
}
