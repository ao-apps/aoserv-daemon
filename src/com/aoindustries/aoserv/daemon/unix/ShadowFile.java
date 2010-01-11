package com.aoindustries.aoserv.daemon.unix;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * A <code>ShadowFileEntry</code> represents one line of the
 * <code>/etc/shadow</code> file on a Unix server.
 *
 * @author  AO Industries, Inc.
 */
final public class ShadowFile {

    public static final UnixFile
        newShadowFile=new UnixFile("/etc/shadow.new"),
        shadowFile=new UnixFile("/etc/shadow"),
        backupShadowFile=new UnixFile("/etc/shadow.old")
    ;

    /** Locks the shadow file for updates */
    private static final Object	shadowLock=new Object();

    private ShadowFile(/*String line*/) {
    }

    /**
     * Gets the encypted password for one user on the system.
     * <p>
     * This method is synchronized with <code>doRebuild</code> to ensure that
     * passwords are never lost during updates.
     */
    public static String getEncryptedPassword(UserId username) throws IOException, ValidationException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

        synchronized(shadowLock) {
            BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
            String password;
            try {
                password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;
                String line;
                while ((line = in.readLine()) != null) {
                    ShadowFileEntry entry = new ShadowFileEntry(line);
                    if(entry.username.equals(username)) {
                        password=entry.password;
                        break;
                    }
                }
            } finally {
                in.close();
            }

            return password;
        }
    }

    public static void rebuildShadowFile(SortedSet<LinuxAccount> accounts) throws IOException, ValidationException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

        synchronized(shadowLock) {
            /*
             * Get the old data from /etc/shadow
             */
            Map<UserId,ShadowFileEntry> shadowEntries = new HashMap<UserId,ShadowFileEntry>();
            BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    ShadowFileEntry entry = new ShadowFileEntry(line);
                    if (shadowEntries.containsKey(entry.username)) throw new IllegalArgumentException("Shadow file contains duplicate entry: " + line);
                    shadowEntries.put(entry.username, entry);
                }
            } finally {
                in.close();
            }

            /*
             * Write the new /etc/shadow file, creating default entries if needed.
             */
            ChainWriter out = new ChainWriter(
                new BufferedOutputStream(
                    newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)
                )
            );
            try {
                boolean rootFound=false;
                for(LinuxAccount account : accounts) {
                    UserId username = account
                        .getUsername()
                        .getUsername()
                    ;
                    if(username.equals(LinuxAccount.ROOT)) {
                        ShadowFileEntry entry = shadowEntries.get(username);
                        if(entry == null) throw new AssertionError("root user not found in /etc/shadow");
                        out.print(entry.toString());
                        out.print('\n');
                        rootFound=true;
                        break;
                    }
                }
                if(!rootFound) throw new AssertionError("root user not found while creating "+newShadowFile.getPath());
                for(LinuxAccount account : accounts) {
                    UserId username = account
                        .getUsername()
                        .getUsername()
                    ;
                    if(!username.equals(LinuxAccount.ROOT)) {
                        ShadowFileEntry entry = shadowEntries.get(username);
                        if (entry == null) entry = new ShadowFileEntry(username);
                        out.print(entry.toString());
                        out.print('\n');
                    }
                }
            } finally {
                out.flush();
                out.close();
            }

            if(newShadowFile.getStat().getSize()>0) {
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    // Do nothing
                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    newShadowFile.setMode(0400);
                } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                shadowFile.renameTo(backupShadowFile);
                newShadowFile.renameTo(shadowFile);
            } else throw new IOException(newShadowFile.getPath()+" has zero or unknown length");
        }
    }

    /**
     * Sets the encypted password for one user on the system.  This password must already
     * be hashed using the crypt or MD5 algorithm.
     * <p>
     * This method is synchronized with <code>doRebuild</code> to ensure that
     * passwords are never lost during updates.
     */
    public static void setEncryptedPassword(UserId username, String encryptedPassword) throws IOException, ValidationException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

        synchronized(shadowLock) {
            /*
             * Get the old data from /etc/shadow
             */
            List<ShadowFileEntry> shadowEntries = new ArrayList<ShadowFileEntry>();
            boolean userFound=false;
            BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
            try {
                // Reset if already exists
                String line;
                while ((line = in.readLine()) != null) {
                    ShadowFileEntry entry = new ShadowFileEntry(line);
                    if(entry.username.equals(username)) {
                        userFound=true;
                        entry.password=encryptedPassword;
                    }
                    shadowEntries.add(entry);
                }
            } finally {
                in.close();
            }

            // Add if does not yet exist
            if(!userFound) shadowEntries.add(new ShadowFileEntry(username+":"+encryptedPassword));

            /*
             * Write the new /etc/shadow file.
             */
            ChainWriter out = new ChainWriter(
                new BufferedOutputStream(
                    newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)
                )
            );
            try {
                for (ShadowFileEntry entry : shadowEntries) {
                    out.print(entry.toString());
                    out.print('\n');
                }
            } finally {
                out.flush();
                out.close();
            }

            /*
             * Move the new file into place.
             */
            shadowFile.renameTo(backupShadowFile);
            newShadowFile.renameTo(shadowFile);
        }
    }

    /**
     * Sets the password for one user on the system.
     */
    public static void setPassword(UserId username, String plaintext) throws IOException, ValidationException {
        setEncryptedPassword(
            username,
            plaintext==null || plaintext.length()==0
            ?LinuxAccount.NO_PASSWORD_CONFIG_VALUE
            :UnixFile.crypt(plaintext, AOServDaemon.getRandom())
        );
    }
}
