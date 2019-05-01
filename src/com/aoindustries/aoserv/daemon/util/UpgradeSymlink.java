/*
 * Copyright 2008-2012, 2015, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.NullArgumentException;
import java.io.IOException;
import java.util.Objects;

/**
 * Manages symbolic links for Tomcat installations.
 *
 * @author  AO Industries, Inc.
 */
public class UpgradeSymlink {

    private final String oldLinkPath;
    private final String oldLinkTarget;
    private final String newLinkPath;
    private final String newLinkTarget;

    /**
     * @param oldLinkPath
     * @param oldLinkTarget  if {@code null} the link will be created if missing
     * @param newLinkPath
     * @param newLinkTarget
     */
    public UpgradeSymlink(String oldLinkPath, String oldLinkTarget, String newLinkPath, String newLinkTarget) {
        if(Objects.equals(oldLinkTarget, newLinkTarget)) throw new IllegalArgumentException("oldLinkTarget = newLinkTarget: " + oldLinkTarget);
		if((newLinkPath == null) != (newLinkTarget == null)) throw new IllegalArgumentException("(newLinkPath == null) != (newLinkTarget == null): (" + newLinkPath + " == null) != (" + newLinkTarget + " == null)");
		if((oldLinkTarget == null) && (newLinkTarget == null)) throw new IllegalArgumentException("(oldLinkTarget == null) && (newLinkTarget == null): (" + oldLinkTarget + " == null) && (" + newLinkTarget + " == null)");
        this.oldLinkPath = NullArgumentException.checkNotNull(oldLinkPath, "oldLinkPath");
        this.oldLinkTarget = oldLinkTarget;
        this.newLinkPath = newLinkPath;
        this.newLinkTarget = newLinkTarget;
    }

    /**
     * @param linkPath
     * @param oldLinkTarget  if {@code null} the link will be created if missing
     * @param newLinkTarget
     */
    public UpgradeSymlink(String linkPath, String oldLinkTarget, String newLinkTarget) {
		this(linkPath, oldLinkTarget, newLinkTarget == null ? null : linkPath, newLinkTarget);
    }

    /**
     * Gets the relative path to the old symlink.
     */
    public String getOldLinkPath() {
        return oldLinkPath;
    }
    
    /**
     * Gets the old target.
     */
    public String getOldLinkTarget() {
        return oldLinkTarget;
    }
    
    /**
     * Gets the relative path to the new symlink or {@code null} if should not exist.
     */
    public String getNewLinkPath() {
        return newLinkPath;
    }

    /**
     * Gets the new link target or {@code null} if should not exist.
     */
    public String getNewLinkTarget() {
        return newLinkTarget;
    }
    
    /**
     * Updates the symlink if needed.  Will also reset the UID
     * and GID if they mismatch.
     * 
     * @return  <code>true</code> if link modified.  UID and GID changes alone will not
     *          count as a change.
     */
    public boolean upgradeLinkTarget(UnixFile baseDirectory, int uid, int gid) throws IOException {
        boolean needsRestart = false;
        UnixFile oldLink = new UnixFile(baseDirectory.getPath() + "/" + oldLinkPath);
        Stat oldLinkStat = oldLink.getStat();
        UnixFile newLink;
		Stat newLinkStat;
		if(newLinkPath == null) {
			newLink = null;
			newLinkStat = null;
		} else if(newLinkPath.equals(oldLinkPath)) {
			newLink = oldLink;
			newLinkStat = oldLinkStat;
		} else {
			newLink = new UnixFile(baseDirectory.getPath() + "/" + newLinkPath);
			newLinkStat = newLink.getStat();
		}
        if(oldLinkTarget == null) {
			assert newLinkPath != null;
			assert newLinkTarget != null;
			assert newLink != null;
			assert newLinkStat != null;
            if(!oldLinkStat.exists() && !newLinkStat.exists()) {
				newLink.symLink(newLinkTarget);
				newLinkStat = newLink.getStat();
				needsRestart = true;
            }
        } else if(oldLinkStat.exists() && oldLinkStat.isSymLink()) {
			if(oldLink.readLink().equals(oldLinkTarget)) {
				oldLink.delete();
				if(newLinkTarget != null) {
					assert newLink != null;
					newLink.symLink(newLinkTarget);
					newLinkStat = newLink.getStat();
				}
				needsRestart = true;
			}
        }
        // Check ownership
        if(
			newLinkStat != null
			&& newLinkStat.exists()
			&& (newLinkStat.getUid() != uid || newLinkStat.getGid() != gid)
		) {
			assert newLink != null;
			newLink.chown(uid, gid);
		}
        return needsRestart;
    }
}
