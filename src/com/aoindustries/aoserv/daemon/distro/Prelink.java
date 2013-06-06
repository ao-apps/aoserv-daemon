/*
 * Copyright 2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoindustries.lang.NotImplementedException;

/**
 * Handles the details of interacting with prelink.
 *
 * @author  AO Industries, Inc.
 */
final public class Prelink {

    private static final String CHROOT_EXE_PATH = "/usr/sbin/chroot";
    private static final String PRELINK_EXE_PATH = "/usr/sbin/prelink";

    /**
     * Make no instances.
     */
    private Prelink() {
    }

	/**
	 * @see #verify(java.lang.String, java.lang.String)
	 */
    public static byte[] verify(String path) {
        return verify(path, null);
    }

    /**
     * Gets the original content of a file before prelinking, also verifying the
     * file has not been tampered with since prelinking.
     *
     * @param  path    the path of the binary or library to verify
     * @param  chroot  the optional chroot path (null for no chroot)
     */
    public static byte[] verify(String path, String chroot) {
		throw new NotImplementedException("TODO: Implement method");
		/*
        String[] command;
        if(chroot!=null) {
            command = new String[] {
                CHROOT_EXE_PATH,
                chroot,
                PRELINK_EXE_PATH,
                "--verify",
                path
            };
        } else {
            command = new String[] {
                PRELINK_EXE_PATH,
                "--verify",
                "--md5",
                path
            };
        }
        try {
            Process P = Runtime.getRuntime().exec(command);
            try {
                P.getOutputStream().close();
                BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    String line = in.readLine();
                    if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
                    String md5 = line.substring(0, 32);
                    SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
                } finally {
                    in.close();
                }
            } finally {
                try {
                    int retCode = P.waitFor();
                    if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
                } catch(InterruptedException err) {
                    IOException ioErr = new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }
            }
        } catch(IOException err) {
            System.err.println("Undoing prelink on \""+osFilename.filename+"\": "+err.toString());
            String[] undoCommand = {
                "/usr/sbin/chroot",
                chroot,
                "/usr/sbin/prelink",
                "--undo",
                osFilename.filename
            };
            AOServDaemon.exec(undoCommand);

            // Try again after undo
            Process P = Runtime.getRuntime().exec(command);
            try {
                P.getOutputStream().close();
                BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    String line = in.readLine();
                    if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
                    String md5 = line.substring(0, 32);
                    SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
                } finally {
                    in.close();
                }
            } finally {
                try {
                    int retCode = P.waitFor();
                    if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
                } catch(InterruptedException err2) {
                    IOException ioErr = new InterruptedIOException();
                    ioErr.initCause(err2);
                    throw ioErr;
                }
            }
        }
		 */
    }
}
