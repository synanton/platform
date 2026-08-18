package org.synanton.common.fs;

import org.synanton.common.error.ForbiddenException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

public class FsPermissionGuard {

    public void checkRead(Path path, int uid, List<Integer> gids) {
        check(path, uid, gids, "READ",
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
    }

    public void checkWrite(Path path, int uid, List<Integer> gids) {
        check(path, uid, gids, "WRITE",
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE);
    }

    private void check(Path path, int uid, List<Integer> gids, String op,
                       PosixFilePermission ownerPerm,
                       PosixFilePermission groupPerm,
                       PosixFilePermission othersPerm) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            int fileOwner = ((Number) Files.getAttribute(path, "unix:uid")).intValue();
            int fileGroup = ((Number) Files.getAttribute(path, "unix:gid")).intValue();

            if (fileOwner == uid && perms.contains(ownerPerm)) return;
            if (gids.contains(fileGroup) && perms.contains(groupPerm)) return;
            if (perms.contains(othersPerm)) return;

            throw new ForbiddenException(
                    "User " + uid + " lacks " + op + " permission on " + path, "ERR_FS_PERMISSION");
        } catch (ForbiddenException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read permissions for " + path, e);
        }
    }
}
