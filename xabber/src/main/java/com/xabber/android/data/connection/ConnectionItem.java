/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.connection;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.LogManager;
import com.xabber.android.data.account.AccountProtocol;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.XMPPConnection;

/**
 * Abstract connection.
 *
 * @author alexander.ivanov
 */
public abstract class ConnectionItem {

    /**
     * Connection options.
     */
    private final ConnectionSettings connectionSettings;

    /**
     * XMPP connection.
     */
    private ConnectionThread connectionThread;

    /**
     * Connection was requested by user.
     */
    private boolean isConnectionRequestedByUser;

    /**
     * Current state.
     */
    private ConnectionState state;

    /**
     * Whether force reconnection is in progress.
     * 是否在进行强制重连
     */
    private boolean disconnectionRequested;

    /**
     * Need to register account on XMPP server.
     */
    private boolean registerNewAccount;

    public ConnectionItem(AccountProtocol protocol, boolean custom,
                          String host, int port, String serverName, String userName,
                          String resource, boolean storePassword, String password,
                          boolean saslEnabled, TLSMode tlsMode, boolean compression,
                          ProxyType proxyType, String proxyHost, int proxyPort,
                          String proxyUser, String proxyPassword) {
        connectionSettings = new ConnectionSettings(protocol, userName,
                serverName, resource, custom, host, port, password,
                saslEnabled, tlsMode, compression, proxyType, proxyHost,
                proxyPort, proxyUser, proxyPassword);
        isConnectionRequestedByUser = false;
        disconnectionRequested = false;
        connectionThread = null;
        state = ConnectionState.offline;
    }

    /**
     * Register new account on server.
     */
    public void registerAccount() {
        registerNewAccount = true;
    }

    /**
     * Report if this connection is to register a new account on XMPP server.
     */
    public boolean isRegisterAccount() {
        return(registerNewAccount);
    }

    /**
     * Gets current connection thread.
     *
     * @return <code>null</code> if thread doesn't exists.
     */
    public ConnectionThread getConnectionThread() {
        return connectionThread;
    }

    /**
     * @return connection options.
     */
    public ConnectionSettings getConnectionSettings() {
        return connectionSettings;
    }

    public ConnectionState getState() {
        return state;
    }

    /**
     * Returns real full jid, that was assigned while login.
     * 返回一个jid,在登陆的时候赋予
     * 如果链接没有成功则返回空值
     * @return <code>null</code> if connection is not established.
     */
    public String getRealJid() {
        ConnectionThread connectionThread = getConnectionThread();
        if (connectionThread == null) {
            return null;
        }
        XMPPConnection xmppConnection = connectionThread.getXMPPConnection();
        if (xmppConnection == null) {
            return null;
        }
        String user = xmppConnection.getUser();
        if (user == null) {
            return null;
        }
        return user;
    }

    /**
     * 判断链接是否可用
     * @param userRequest action was requested by user.
     * @return Whether connection is available.
     */
    protected boolean isConnectionAvailable(boolean userRequest) {
        return true;
    }

    /**
     * Connect or disconnect from server depending on internal flags.
     * 链接或者失去链接基于本地的flags
     * @param userRequest action was requested by user.
     * @return Whether state has been changed.是否状态已经改变了
     */
    public boolean updateConnection(boolean userRequest) {
        boolean available = isConnectionAvailable(userRequest);
        if (NetworkManager.getInstance().getState() != NetworkState.available
                || !available || disconnectionRequested) { //如果没有网络，那么只存在两种状态，等待或者离线
            ConnectionState target = available ? ConnectionState.waiting : ConnectionState.offline;
            if (state == ConnectionState.connected || state == ConnectionState.authentication
                    || state == ConnectionState.connecting) {
                if (userRequest) {
                    isConnectionRequestedByUser = false;
                }
                if (connectionThread != null) {
                    disconnect(connectionThread);
                    // Force remove managed connection thread.
                    onClose(connectionThread);
                    connectionThread = null;
                }
            } else if (state == target) {
                return false;
            }
            state = target;
            return true;
        } else {
            if (state == ConnectionState.offline || state == ConnectionState.waiting) { //如果有网络，且没有连上
                if (userRequest) {
                    isConnectionRequestedByUser = true;
                }
                state = ConnectionState.connecting;
                connectionThread = new ConnectionThread(this);

                boolean useSRVLookup;
                String fullyQualifiedDomainName;
                int port;
                if (connectionSettings.isCustomHostAndPort()) {
                    fullyQualifiedDomainName = connectionSettings.getHost();
                    port = connectionSettings.getPort();
                    useSRVLookup = false;
                } else {
                    fullyQualifiedDomainName = connectionSettings.getServerName();
                    port = 5222;
                    useSRVLookup = true;
                }

                connectionThread.start(fullyQualifiedDomainName, port, useSRVLookup, registerNewAccount); //开始链接

                return true;
            } else {
                return false; //如果状态不为离线或者等待，那么就不需要更新了
            }
        }
    }

    /**
     * Disconnect and connect using new connection.
     */
    public void forceReconnect() {
        if (!getState().isConnectable()) { //判断状态是否可用
            return;
        }
        disconnectionRequested = true;
        boolean request = isConnectionRequestedByUser;
        isConnectionRequestedByUser = false;
        updateConnection(false);
        isConnectionRequestedByUser = request;
        disconnectionRequested = false;
        updateConnection(false);
    }

    /**
     * Starts disconnection in another thread.
     */
    protected void disconnect(final ConnectionThread connectionThread) {
        Thread thread = new Thread("Disconnection thread for " + this) {
            @Override
            public void run() {
                AbstractXMPPConnection xmppConnection = connectionThread.getXMPPConnection();
                if (xmppConnection != null)
                    try {
                        xmppConnection.disconnect();
                    } catch (RuntimeException e) {
                        // connectionClose() in smack can fail.
                    }
            }

        };
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * @param connectionThread
     * @return Whether thread is managed by connection.
     */
    boolean isManaged(ConnectionThread connectionThread) {
        return connectionThread == this.connectionThread;
    }

    /**
     * Update password.
     *
     * @param password
     */
    protected void onPasswordChanged(String password) {
        connectionSettings.setPassword(password);
    }

    /**
     * SRV record has been resolved.
     */
    protected void onSRVResolved(ConnectionThread connectionThread) {
    }

    /**
     * Invalid certificate has been received.
     * 接受了一个无效的证书
     *
     */
    protected void onInvalidCertificate() {
    }

    /**
     * Connection has been established.
     * 链接已经建立了
     */
    protected void onConnected(ConnectionThread connectionThread) {
        if (isRegisterAccount()) {
            state = ConnectionState.registration;
        } else if (isManaged(connectionThread)) {
            state = ConnectionState.authentication;
        }
    }

    /**
     * New account has been registered on XMPP server.
     * 新账户已经注册完成，状态变为认证中
     */
    protected void onAccountRegistered(ConnectionThread connectionThread) {
        registerNewAccount = false;
        if (isManaged(connectionThread)) {
            state = ConnectionState.authentication;
        }
    }

    /**
     * Authorization failed.
     * 认证失败
     */
    protected void onAuthFailed() {
    }

    /**
     * Authorization passed.
     * 认证通过
     */
    protected void onAuthorized(ConnectionThread connectionThread) {
        if (isManaged(connectionThread)) {
            state = ConnectionState.connected;
        }
    }

    /**
     * Called when disconnect should occur.
     * 当断开链接时调用
     *
     * @param connectionThread
     * @return <code>true</code> if connection thread was managed.
     */
    private boolean onDisconnect(ConnectionThread connectionThread) {
        XMPPConnection xmppConnection = connectionThread.getXMPPConnection();
        boolean acceptable = isManaged(connectionThread);
        if (xmppConnection == null) {
            LogManager.i(this, "onClose " + acceptable);
        } else {
            LogManager.i(this, "onClose " + xmppConnection.hashCode() + " - "
                            + xmppConnection.getConnectionCounter() + ", " + acceptable);
        }

        ConnectionManager.getInstance().onDisconnect(connectionThread);
        if (acceptable) {
            connectionThread.shutdown();
        }
        return acceptable;
    }

    /**
     * Called when connection was closed for some reason.
     * 当链接因为一些原因关闭时调用
     */
    protected void onClose(ConnectionThread connectionThread) {
        if (onDisconnect(connectionThread)) {
            state = ConnectionState.waiting;
            this.connectionThread = null;
            if (isConnectionRequestedByUser) {
                Application.getInstance().onError(R.string.CONNECTION_FAILED);
            }
            isConnectionRequestedByUser = false;
        }
    }

    /**
     * Called when another host should be used.
     * 需要使用另一个地址时使用
     * @param connectionThread
     * @param fqdn
     * @param port
     * @param useSrvLookup
     */
    protected void onSeeOtherHost(ConnectionThread connectionThread,
                                  String fqdn, int port, boolean useSrvLookup) {
        // TODO: Check for number of redirects.
        if (onDisconnect(connectionThread)) {
            state = ConnectionState.connecting;
            this.connectionThread = new ConnectionThread(this);
            this.connectionThread.start(fqdn, port, useSrvLookup, registerNewAccount);
        }
    }

}
