/*
 * Copyright 2008-2009, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.io.FileUtils;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable file utilities.
 *
 * @author  AO Industries, Inc.
 */
public class DaemonFileUtils {

	private final static Logger logger = Logger.getLogger(DaemonFileUtils.class.getName());

	/**
	 * Make no instances.
	 */
	private DaemonFileUtils() {
	}

    /**
     * Copies a resource to the provided output stream.
     */
    public static void copyResource(Class<?> clazz, String resource, OutputStream out) throws IOException {
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            if(in==null) throw new IOException("Unable to find resource: "+resource);
			IoUtils.copy(in, out);
        }
    }

    /**
     * Copies a resource to the provided filename, will not overwrite any existing file.
     * 
     * TODO: Copy to a temp file and rename into place.
     */
    public static void copyResource(Class<?> clazz, String resource, String filename, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException {
        try (OutputStream out = new UnixFile(filename).getSecureOutputStream(uid, gid, mode, false, uid_min, gid_min)) {
            copyResource(clazz, resource, out);
        }
    }
    
    /**
     * Creates a symbolic link.  To aid in debugging, writes the filename and target to System.err if
     * an <code>IOException</code> occurs; exception is then rethrown.
     */
    public static void ln(String target, String filename, int uid, int gid) throws IOException {
        try {
            new UnixFile(filename).symLink(target).chown(uid, gid);
        } catch (IOException e) {
            System.err.println("ln: filename: "+filename+"   destination: "+target);
            throw e;
        }
    }
    
    /**
     * Creates symbolic links to all items in a directory.
     * 
     * @see  #ln(String,String,int,int)
     */
    public static void lnAll(String targetBase, String srcBase, int uid, int gid) throws IOException {
        String[] destinations=new UnixFile(targetBase).list();
		for (String destination : destinations) {
			ln(targetBase + destination, srcBase + destination, uid, gid);
		}
    }

    /**
     * Creates a directory, if needed.  If already exists makes sure it is a directory.
     * Also sets or resets the ownership and permissions.
     */
    public static void mkdir(String dirName, int mode, int uid, int gid) throws IOException {
        mkdir(new UnixFile(dirName), mode, uid, gid);
    }

    /**
     * Creates a directory, if needed.  If already exists makes sure it is a directory.
     * Also sets or resets the ownership and permissions.
     */
    public static void mkdir(UnixFile uf, int mode, int uid, int gid) throws IOException {
        Stat ufStat = uf.getStat();
        if(!ufStat.exists()) {
            uf.mkdir();
            ufStat = uf.getStat();
        } else if(!ufStat.isDirectory()) throw new IOException("File exists and is not a directory: "+uf.getPath());
        if(ufStat.getMode()!=mode) uf.setMode(mode);
        if(ufStat.getUid()!=uid || ufStat.getGid()!=gid) uf.chown(uid, gid);
    }

    /**
     * If the file starts with the provided prefix, strips that prefix from the
     * file.  A new temp file is created and then renamed over the old.
     */
    public static void stripFilePrefix(UnixFile uf, String prefix, int uid_min, int gid_min) throws IOException {
        // Remove the auto warning if the site has recently become manual
        int prefixLen=prefix.length();
        Stat ufStat = uf.getStat();
        if(ufStat.getSize()>=prefixLen) {
            UnixFile newUF=null;
            try (InputStream in = new BufferedInputStream(uf.getSecureInputStream(uid_min, gid_min))) {
                StringBuilder SB=new StringBuilder(prefixLen);
                int ch;
                while(SB.length()<prefixLen && (ch=in.read())!=-1) {
                    SB.append((char)ch);
                }
                if(SB.toString().equals(prefix)) {
                    newUF=UnixFile.mktemp(uf.getPath()+'.', false);
                    try (OutputStream out = new BufferedOutputStream(
						newUF.getSecureOutputStream(
							ufStat.getUid(),
							ufStat.getGid(),
							ufStat.getMode(),
							true,
							uid_min,
							gid_min
						)
					)) {
						IoUtils.copy(in, out);
                    }
                }
            }
            if(newUF!=null) newUF.renameTo(uf);
        }
    }

    /**
     * Overwrites a file only when missing or not equal to the provided
     * content.  Will first create a new file into <code>tempFile</code>,
     * set the ownership and permissions, and then rename over any
     * existing files.  If <code>tempFile</code> exists and <code>file</code>
     * has to be written, <code>tempFile</code> will be overwritten.
     * If file is a symlink, will be removed first.
     * 
     * @param  tempFile  if <code>null</code>, a randomly-generated filename will
     *                   be used
     *
     * @return  <code>true</code> if the file was created or overwritten,
     *          <code>false</code> when no changes were made
     */
    public static boolean writeIfNeeded(
        byte[] newContents,
        UnixFile tempFile,
        UnixFile file,
        int uid,
        int gid,
        int mode,
		int uid_min,
		int gid_min
    ) throws IOException {
        Stat fileStat = file.getStat();
        if(fileStat.exists() && fileStat.isSymLink()) {
            file.delete();
            fileStat = file.getStat();
        }
        if(
            !fileStat.exists()
            || !file.contentEquals(newContents)
        ) {
            // Create temp file if none provided
            if(tempFile==null) tempFile = UnixFile.mktemp(file.getPath()+".", false);
            try {
                try (
					FileOutputStream newOut = tempFile.getSecureOutputStream(
						uid,
						gid,
						mode,
						true,
						uid_min,
						gid_min
					)
				) {
                    newOut.write(newContents);
                }
                tempFile.renameTo(file);
            } finally {
                // If newFile still exists there was a problem and it should be
                // cleaned-up
                if(tempFile.getStat().exists()) tempFile.delete();
            }
            return true;
        } else {
            return false;
        }
    }

	/**
	 * Atomically replaces a file.  The file will always exist and will always
	 * be either the old of new version.
	 * <p>
	 * Will not overwrite the file if the contents already match,
	 * but the permissions and ownership will still be verified.
	 * </p>
	 * <p>
	 * New file contents, both for the file and its optional backup, are written
	 * to temp files and then atomically renamed into place, with the backup
	 * renamed into place first.
	 * </p>
	 *
	 * @param  file  the file to overwrite
	 * @param  backupFile  the optional backup file
	 */
	public static void atomicWrite(UnixFile file, UnixFile backupFile, byte[] newContents, long mode, int uid, int gid) throws IOException {
		Stat fileStat = file.getStat();
		if(
			!fileStat.exists()
			|| !file.contentEquals(newContents)
		) {
			UnixFile backupTemp;
			if(backupFile != null && fileStat.exists()) {
				// Create temp backup
				backupTemp = UnixFile.mktemp(backupFile.getPath(), false);
				if(logger.isLoggable(Level.FINE)) logger.fine("mktemp \"" + backupFile + "\" -> \"" + backupTemp + '"');
				long numBytes = FileUtils.copy(file.getFile(), backupTemp.getFile());
				if(logger.isLoggable(Level.FINE)) logger.fine("cp \"" + file + "\" \"" + backupTemp + "\", " + numBytes + " bytes copied");
				if(fileStat.getSize() != numBytes) throw new IOException("File size mismatch: " + fileStat.getSize() + " != " + numBytes);
				Stat backupTempStat = backupTemp.getStat();
				if(backupTempStat.getSize() != numBytes) throw new IOException("File size mismatch: " + backupTempStat.getSize() + " != " + numBytes);
				// Set ownership
				if(backupTempStat.getUid() != uid || backupTempStat.getGid() != gid) {
					if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + backupTemp + '"');
					backupTemp.chown(uid, gid);
				}
				// Set permissions
				if(backupTempStat.getMode() != mode) {
					if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + backupTemp + '"');
					backupTemp.setMode(mode);
				}
				// Set modified time
				if(
					backupTempStat.getAccessTime() != fileStat.getAccessTime()
					|| backupTempStat.getModifyTime() != fileStat.getModifyTime()
				) {
					if(logger.isLoggable(Level.FINE)) logger.fine("utime " + fileStat.getAccessTime() + ' ' + fileStat.getModifyTime() + " \"" + backupTemp + '"');
					backupTemp.utime(
						fileStat.getAccessTime(),
						fileStat.getModifyTime()
					);
				}
			} else {
				// No backup
				backupTemp = null;
			}
			// Write the new contents into a temp file
			UnixFile fileTemp = UnixFile.mktemp(file.getPath(), false);
			if(logger.isLoggable(Level.FINE)) logger.fine("mktemp \"" + file + "\" -> \"" + fileTemp + '"');
			try (FileOutputStream out = new FileOutputStream(fileTemp.getFile())) {
				out.write(newContents);
			}
			if(logger.isLoggable(Level.FINE)) logger.fine("Wrote " + newContents.length + " bytes to \"" + fileTemp + '"');
			Stat fileTempStat = fileTemp.getStat();
			if(fileTempStat.getSize() != newContents.length) throw new IOException("File size mismatch: " + fileTempStat.getSize() + " != " + newContents.length);
			// Set ownership
			if(fileTempStat.getUid() != uid || fileTempStat.getGid() != gid) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + fileTemp + '"');
				fileTemp.chown(uid, gid);
			}
			// Set permissions
			if(fileTempStat.getMode() != mode) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + fileTemp + '"');
				fileTemp.setMode(mode);
			}
			// Move backup into place
			if(backupTemp != null) {
				if(logger.isLoggable(Level.FINE)) logger.fine("mv \"" + backupTemp + "\" \"" + backupFile + '"');
				backupTemp.renameTo(backupFile);
			}
			// Move file into place
			if(logger.isLoggable(Level.FINE)) logger.fine("mv \"" + fileTemp + "\" \"" + file + '"');
			fileTemp.renameTo(file);
		} else {
			// Verify ownership
			if(fileStat.getUid() != uid || fileStat.getGid() != gid) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + file + '"');
				file.chown(uid, gid);
			}
			// Verify permissions
			if(fileStat.getMode() != mode) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + file + '"');
				file.setMode(mode);
			}
		}
	}
}
