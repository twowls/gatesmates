/*
 * Copyright (c) 2016 Twowls.org.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.twowls.gatesmates.registry;

import org.twowls.gatesmates.util.Gates;
import org.twowls.gatesmates.util.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * <p>Provides utility methods for accessing Windows registry.</p>
 *
 * @author bubo &lt;bubo@twowls.org&gt;
 */
public final class Registry implements RegistryConst {

    /** Registry key for the current user */
    public static final Key KEY_CURRENT_USER = Key.forHandle(HKEY_CURRENT_USER);
    /** Registry key for the local machine */
    public static final Key KEY_LOCAL_MACHINE = Key.forHandle(HKEY_LOCAL_MACHINE);

    private static boolean available = Gates.isAvailable();

    /**
     * <p>Opens a registry key for reading.</p>
     * @param rootKey the root key that sought key belongs to
     * @param subPath sub key path relative to root key
     * @return a {@link Key} instance representing key resource
     * @throws RegistryException if a problem occurred while opening key
     */
    public static Key openKey(Key rootKey, String subPath) throws RegistryException {
        return openKey(rootKey, subPath, false);
    }

    /**
     * <p>Opens a registry key for reading or writing.</p>
     * @param rootKey the root key that sought key belongs to
     * @param subPath sub key path relative to root key
     * @param forWriting {@code true} if write access requested, otherwise {@code false}
     * @return a {@link Key} instance representing key resource
     * @throws RegistryException if a problem occurred while opening key
     */
    public static Key openKey(Key rootKey, String subPath, boolean forWriting)
            throws RegistryException {

        checkAvailable();
        Objects.requireNonNull(rootKey, "Root key must not be null");
        Objects.requireNonNull(subPath, "Sub key path must not be null");

        int[] handleBuffer = createBuffer(0);
        int err = Gates.AdvApi32.RegOpenKeyExA(rootKey.handle, toWindowsPath(subPath), REG_OPTION_OPEN_LINK,
                (forWriting ? KEY_WRITE : KEY_READ) | KEY_WOW64_64KEY, handleBuffer);

        if (ERROR_SUCCESS != err) {
            throw new RegistryException(err, "Could not open registry key '" + subPath
                    + "' for " + (forWriting ? "writing" : "reading"));
        }

        return Key.forHandle(handleBuffer[0]);
    }

    /**
     * <p>Queries unnamed property value of the given key.</p>
     * @param key a {@link Key} previously open with {@link #openKey(Key, String, boolean)}
     * @param fallbackValue value to return if unnamed value is undefined to the given key
     * @return the value of unnamed property
     * @throws RegistryException if registry is not available or value cannot be read
     */
    public static String queryUnnamedValue(Key key, String fallbackValue)
            throws RegistryException {
        try {
            return queryUnnamedValue(key);
        } catch (RegistryException e) {
            if (ERROR_NOT_FOUND == e.getErrorCode()) {
                return fallbackValue;
            }
            throw e;
        }
    }

    /**
     * <p>Queries unnamed property value of the given key.</p>
     * @param key a {@link Key} previously open with {@link #openKey(Key, String, boolean)}
     * @return the value of unnamed property
     * @throws RegistryException if registry is not available or value cannot be read
     */
    public static String queryUnnamedValue(Key key) throws RegistryException {
        return queryStringValue(key, "");
    }

    /**
     * <p>Queries value of a named textual property ({@code REG_SZ} or similar).</p>
     * @param key registry key previously open with {@link #openKey(Key, String, boolean)}
     * @param valueName the name of the property being queried
     * @param fallbackValue the value to return if property does not actually exists
     * @return property value or {@code fallbackValue} if property does not exist
     * @throws RegistryException if registry is not available or actual property type is not textual
     */
    public static String queryStringValue(Key key, String valueName, String fallbackValue)
            throws RegistryException {
        try {
            return queryStringValue(key, valueName);
        } catch (RegistryException e) {
            if (ERROR_NOT_FOUND == e.getErrorCode()) {
                return fallbackValue;
            }
            throw e;
        }
    }

    /**
     * <p>Queries value of a named textual property ({@code REG_SZ} or similar).</p>
     * @param key registry key previously open with {@link #openKey(Key, String, boolean)}
     * @param valueName the name of the property being queried
     * @return property value or {@code fallbackValue} if property does not exist
     * @throws RegistryException if registry is not available or property does not exists
     * or actual property type is not textual
     */
    public static String queryStringValue(Key key, String valueName)
            throws RegistryException {

        // first query returns actual type of the property and necessary buffer size
        int[] info = queryValue0(key, valueName, null);
        if (REG_SZ != info[0] && REG_EXPAND_SZ != info[0]) {
            throw new RegistryException(RegistryException.VALUE_TYPE_MISMATCH,
                    "Actual property type is not textual");
        }

        // second query actually read value into the buffer
        byte[] data = new byte[info[1]];
        queryValue0(key, valueName, data);
        return stringFromByteArray(data);
    }

    /**
     * <p>Queries value of a named numeric property ({@code REG_DWORD}).</p>
     * @param key registry key previously open with {@link #openKey(Key, String, boolean)}
     * @param valueName the name of the property being queried
     * @param fallbackValue the value to return if property does not exists
     * @return property value or {@code fallbackValue} if property does not exist
     * @throws RegistryException if registry is not available or actual property type is not numeric
     */
    public static Integer queryIntValue(Key key, String valueName, Integer fallbackValue)
            throws RegistryException {
        try {
            return queryIntValue(key, valueName);
        } catch (RegistryException e) {
            if (ERROR_NOT_FOUND == e.getErrorCode()) {
                return fallbackValue;
            }
            throw e;
        }
    }

    /**
     * <p>Queries value of a named numeric property ({@code REG_DWORD}).</p>
     * @param key registry key previously open with {@link #openKey(Key, String, boolean)}
     * @param valueName the name of the property being queried
     * @return property value or {@code fallbackValue} if property does not exist
     * @throws RegistryException if registry is not available or property
     *  does not exists or actual property type is not numeric
     */
    public static int queryIntValue(Key key, String valueName) throws RegistryException {
        // first query returns actual type of the property and necessary buffer size
        int[] info = queryValue0(key, valueName, null);
        if (REG_DWORD != info[0] && REG_DWORD_BIG_ENDIAN != info[0]) {
            throw new RegistryException(RegistryException.VALUE_TYPE_MISMATCH,
                    "Actual property type is not numeric");
        }

        // second query actually read value into the buffer
        byte[] data = new byte[info[1]];
        queryValue0(key, valueName, data);

        // convert byte array to number according to property byte-order
        return ByteBuffer.wrap(data).order(REG_DWORD_BIG_ENDIAN == info[0] ?
                ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public static void closeKey(Key key) throws RegistryException {
        checkAvailable();
        if (key != null) {
            try {
                int result = Gates.AdvApi32.RegCloseKey(key.handle);
                if (ERROR_SUCCESS != result) {
                    throw new RegistryException(result, "Could not close key");
                }
            } finally {
                key.handle = 0;
            }
        }
    }

    static void forceAvailable() {
        available = true;
    }

    static String toWindowsPath(String s) {
        return (s == null ? null : s.replace('/', '\\'));
    }

    private static int[] queryValue0(Key key, String valueName, byte[] buffer) throws RegistryException {
        checkAvailable();
        Objects.requireNonNull(key, "Key must not be null");
        Objects.requireNonNull(valueName, "Value name must not be null");

        int[] typeBuffer = createBuffer(0), sizeBuffer = createBuffer(buffer == null ? 0 : buffer.length);
        int err = Gates.AdvApi32.RegQueryValueExA(key.handle, valueName, null, typeBuffer, buffer, sizeBuffer);
        if (err != ERROR_SUCCESS) {
            throw new RegistryException(err, "Failed to query value '" + valueName + "'");
        }

        return createBuffer(typeBuffer[0], sizeBuffer[0]);
    }

    private static void checkAvailable() throws RegistryException {
        if (!available) {
            throw new RegistryException(RegistryException.UNAVAILABLE, "Registry is not available");
        }
    }

    private static String stringFromByteArray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        } else {
            int len = 0;
            for (byte b : bytes) {
                if (b == 0) break;
                len++;
            }
            return new String(bytes, 0, len);
        }
    }

    private static int[] createBuffer(int... values) {
        return Arrays.copyOf(values, values.length);
    }

    /** Internal representation of a registry key */
    public static class Key implements Handle {

        private int handle;

        private Key(int handle) {
            this.handle = handle;
        }

        public Key openSubKey(String subPath) throws RegistryException {
            return Registry.openKey(this, subPath);
        }

        public Key openSubKey(String subPath, boolean forWriting) throws RegistryException {
            return Registry.openKey(this, subPath, forWriting);
        }

        public String queryUnnamedValue() throws RegistryException {
            return Registry.queryUnnamedValue(this);
        }

        public String queryUnnamedValue(String fallback) throws RegistryException {
            return Registry.queryUnnamedValue(this, fallback);
        }

        public String queryStringValue(String valueName) throws RegistryException {
            return Registry.queryStringValue(this, valueName);
        }

        public String queryStringValue(String valueName, String fallback) throws RegistryException {
            return Registry.queryStringValue(this, valueName, fallback);
        }

        public int queryIntValue(String valueName) throws RegistryException {
            return Registry.queryIntValue(this, valueName);
        }

        public Integer queryIntValue(String valueName, Integer fallback) throws RegistryException {
            return Registry.queryIntValue(this, valueName, fallback);
        }

        @Override
        public void close() throws RegistryException {
            Registry.closeKey(this);
        }

        @Override
        public String toString() {
            String textualHandle;
            switch (handle) {
                case HKEY_CLASSES_ROOT:
                    textualHandle = "HKEY_CLASSES_ROOT";
                    break;
                case HKEY_CURRENT_USER:
                    textualHandle = "HKEY_CURRENT_USER";
                    break;
                case HKEY_LOCAL_MACHINE:
                    textualHandle = "HKEY_LOCAL_MACHINE";
                    break;
                default:
                    textualHandle = "0x" + Integer.toHexString(handle);
            }
            return this.getClass().getCanonicalName() + " (" + textualHandle + ")";
        }

        /**
         * <p>Creates a new {@link Key} object for the given {@code handle}.</p>
         * @param handle the value of system handle
         * @return a new instance of {@link Key}
         */
        static Key forHandle(int handle) {
            return new Key(handle);
        }
    }

    /* Prohibits instantiation */
    private Registry() {}
}
