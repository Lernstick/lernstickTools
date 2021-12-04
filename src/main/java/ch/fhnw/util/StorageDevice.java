package ch.fhnw.util;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.freedesktop.dbus.exceptions.DBusException;
import org.xml.sax.SAXException;

/**
 * A storage device
 *
 * @author Ronny Standtke <Ronny.Standtke@gmx.net>
 */
public class StorageDevice implements Comparable<StorageDevice> {

    /**
     * all known types of storage devices
     */
    public enum Type {

        /**
         * a CD or DVD
         */
        OpticalDisc,
        /**
         * a hard drive
         */
        HardDrive,
        /**
         * a usb flash drive
         */
        USBFlashDrive,
        /**
         * a secure digital memory card
         */
        SDMemoryCard,
        /**
         * NVM Express
         */
        NVMe
    }

    /**
     * all known variants of upgrading the EFI partition
     */
    public enum EfiUpgradeVariant {

        /**
         * The EFI partition is either newly created or the old content on the
         * EFI partition is replaced with the current version.
         */
        REGULAR,
        /**
         * The EFI partition must be enlarged before upgrading. This is done by
         * shrinking the data partition.
         */
        ENLARGE_REPARTITION,
        /**
         * The EFI partition must be enlarged before upgrading. This is done by
         * backing up the exchange partition. After installation the backed up
         * data is restored to the exchange partition.
         */
        ENLARGE_BACKUP
    }

    /**
     * all known variants of upgrading the system partition
     */
    public enum SystemUpgradeVariant {

        /**
         * The persistency partition is cleaned (excluding the users home
         * directory and the cups configuration) and the old content on the
         * system partition is replaced with the current version.
         */
        REGULAR,
        /**
         * The system partition must be enlarged before upgrading. This is done
         * by shrinking the data partition.
         */
        REPARTITION,
        /**
         * The personal data and configuration is backed up before executing a
         * completely new installation on the storage device. After installation
         * the backed up data is restored.
         */
        BACKUP,
        /**
         * Upgrading is done by a clean default installation.
         */
        INSTALLATION,
        /**
         * The system partition can not be upgraded.
         */
        IMPOSSIBLE
    }

    private static final Logger LOGGER
            = Logger.getLogger(StorageDevice.class.getName());
    private static final ResourceBundle STRINGS
            = ResourceBundle.getBundle("ch/fhnw/util/Strings");
    private final String device;
    private String vendor;
    private String model;
    private String revision;
    private String serial;
    private boolean raid;
    private String raidLevel;
    private int raidDeviceCount;
    private boolean removable;
    private long size;
    private final Type type;
    private List<Partition> partitions;
    private EfiUpgradeVariant efiUpgradeVariant;
    private SystemUpgradeVariant systemUpgradeVariant;
    private String noUpgradeReason;
    private Partition exchangePartition;
    private Partition dataPartition;
    private Partition efiPartition;
    private Partition systemPartition;

    /**
     * creates a new StorageDevice
     *
     * @param device the unix device name, e.g. "sda"
     * @throws DBusException if getting the device properties via d-bus fails
     */
    public StorageDevice(String device) throws DBusException {
        this.device = device;
        boolean isOpticalDisc = false;
        String connectionInterface = null;

        /*
         * The Udisks2 interface "Drive" reports *all* USB drives as removable,
         * even those where /sys/block/[device]/removable is '0'.
         * Because the value in /sys/block/[device]/removable is the correct one
         * we use it instead of the more "modern" Udisks2 interface. :-P
         */
        try {
            removable = new FileReader(
                    "/sys/block/" + device + "/removable").read() == '1';
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }

        boolean systemInternal = false;

        if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {
            vendor = DbusTools.getStringProperty(device, "DriveVendor");
            model = DbusTools.getStringProperty(device, "DriveModel");
            revision = DbusTools.getStringProperty(device, "DriveRevision");
            serial = DbusTools.getStringProperty(device, "DriveSerial");
            size = DbusTools.getLongProperty(device, "DeviceSize");
            systemInternal = DbusTools.getBooleanProperty(
                    device, "DeviceIsSystemInternal");
            connectionInterface = DbusTools.getStringProperty(
                    device, "DriveConnectionInterface");
            isOpticalDisc = DbusTools.getBooleanProperty(
                    device, "DeviceIsOpticalDisc");

        } else {

            try {
                List<String> interfaceNames
                        = DbusTools.getDeviceInterfaceNames(device);
                String blockInterfaceName = "org.freedesktop.UDisks2.Block";
                if (interfaceNames.contains(blockInterfaceName)) {
                    // query block device specific properties
                    systemInternal = DbusTools.getDeviceBooleanProperty(
                            device, blockInterfaceName, "HintSystem");

                    // query drive specific properties
                    String driveObjectPath = DbusTools.getDevicePathProperty(
                            device, blockInterfaceName, "Drive").toString();
                    if (driveObjectPath.equals("/")) {
                        // raid device
                        raid = true;
                        String raidObjectPath = DbusTools.getDevicePathProperty(
                                device, blockInterfaceName, "MDRaid").toString();
                        String raidInterface = "org.freedesktop.UDisks2.MDRaid";
                        raidLevel = DbusTools.getStringProperty(
                                raidObjectPath, raidInterface, "Level");
                        raidDeviceCount = DbusTools.getIntProperty(
                                raidObjectPath, raidInterface, "NumDevices");
                        size = DbusTools.getLongProperty(
                                raidObjectPath, raidInterface, "Size");
                    } else {
                        // non-raid device
                        String driveInterface = "org.freedesktop.UDisks2.Drive";
                        vendor = DbusTools.getStringProperty(
                                driveObjectPath, driveInterface, "Vendor");
                        model = DbusTools.getStringProperty(
                                driveObjectPath, driveInterface, "Model");
                        revision = DbusTools.getStringProperty(
                                driveObjectPath, driveInterface, "Revision");
                        serial = DbusTools.getStringProperty(
                                driveObjectPath, driveInterface, "Serial");
                        size = DbusTools.getLongProperty(
                                driveObjectPath, driveInterface, "Size");
                        connectionInterface = DbusTools.getStringProperty(
                                driveObjectPath, driveInterface, "ConnectionBus");
                        isOpticalDisc = DbusTools.getBooleanProperty(
                                driveObjectPath, driveInterface, "Optical");
                    }
                }
            } catch (SAXException | IOException
                    | ParserConfigurationException ex) {
                LOGGER.log(Level.SEVERE, "", ex);
            }
        }

        // little stupid heuristic to determine storage device type
        if (isOpticalDisc) {
            type = Type.OpticalDisc;
        } else if (device.startsWith("mmcblk")) {
            type = Type.SDMemoryCard;
        } else if (device.startsWith("nvme")) {
            type = Type.NVMe;
        } else if ((systemInternal == false)
                && "usb".equals(connectionInterface)) {
            type = Type.USBFlashDrive;
        } else {
            type = Type.HardDrive;
        }
    }

    /**
     * creates a new StorageDevice
     *
     * @param mountPoint the mount point of the storage device
     * @return a new StorageDevice
     * @throws DBusException if getting the device properties via d-bus fails
     * @throws IOException if reading "/proc/mounts" fails
     */
    public static StorageDevice getStorageDeviceFromMountPoint(
            String mountPoint) throws DBusException, IOException {

        LOGGER.log(Level.FINE, "mountPoint: \"{0}\"", mountPoint);

        List<String> mounts = LernstickFileTools.readFile(
                new File("/proc/mounts"));
        for (String mount : mounts) {
            String[] tokens = mount.split(" ");
            if (tokens[0].startsWith("/dev/") && tokens[1].equals(mountPoint)) {
                return new StorageDevice(tokens[0].substring(5));
            }
        }

        return null;
    }

    @Override
    public int compareTo(StorageDevice other) {
        return device.compareTo(other.getDevice());
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof StorageDevice) {
            StorageDevice otherDevice = (StorageDevice) other;
            return otherDevice.getDevice().equals(device)
                    && otherDevice.getSize() == size;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 41 * hash + (device != null ? device.hashCode() : 0);
        hash = 41 * hash + (int) (size ^ (size >>> 32));
        return hash;
    }

    @Override
    public String toString() {
        return device + ", " + type + ", "
                + LernstickFileTools.getDataVolumeString(size, 1);
    }

    /**
     * returns the type of this storage device
     *
     * @return the type of this storage device
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the device node of the storage device (e.g. sda).
     *
     * @return the device node of the storage device (e.g. sda)
     */
    public String getDevice() {
        return device;
    }

    /**
     * Returns the full device node of the storage device (e.g. /dev/sda).
     *
     * @return the full device node of the storage device (e.g. /dev/sda)
     */
    public String getFullDevice() {
        return "/dev/" + device;
    }

    /**
     * returns the storage device vendor
     *
     * @return the storage device vendor
     */
    public String getVendor() {
        return vendor;
    }

    /**
     * returns the storage device model
     *
     * @return the storage device model
     */
    public String getModel() {
        return model;
    }

    /**
     * returns the revision of this storage device
     *
     * @return the revision of this storage device
     */
    public String getRevision() {
        return revision;
    }

    /**
     * returns the serial of the storage device
     *
     * @return the serial of the storage device
     */
    public String getSerial() {
        return serial;
    }

    /**
     * returns the size of the storage device (in Byte)
     *
     * @return the size of the storage device (in Byte)
     */
    public long getSize() {
        return size;
    }

    /**
     * returns <code>true</code>, if this device is removable,
     * <code>false</code> otherwise
     *
     * @return <code>true</code>, if this device is removable,
     * <code>false</code> otherwise
     */
    public boolean isRemovable() {
        return removable;
    }

    /**
     * returns <code>true</code>, if this device is a RAID, <code>false</code>
     * otherwise
     *
     * @return <code>true</code>, if this device is a RAID, <code>false</code>
     * otherwise
     */
    public boolean isRaid() {
        return raid;
    }

    /**
     * returns the RAID level or <code>null</code>, if this is no RAID
     *
     * @return the RAID level or <code>null</code>, if this is no RAID
     */
    public String getRaidLevel() {
        return raidLevel;
    }

    /**
     * returns the number of devices in this RAID
     *
     * @return the number of devices in this RAID
     */
    public int getRaidDeviceCount() {
        return raidDeviceCount;
    }

    /**
     * returns the list of partitions of this storage device
     *
     * @return the list of partitions of this storage device
     */
    public synchronized List<Partition> getPartitions() {
        if (partitions == null) {
            // create new list
            partitions = new ArrayList<>();

            List<String> partitionStings = DbusTools.getPartitions();
            Pattern pattern;
            if (device.startsWith("mmcblk") || device.startsWith("nvme")) {
                pattern = Pattern.compile(device + "(p\\d+)");
            } else {
                pattern = Pattern.compile(device + "(\\d+)");
            }
            for (String partitionString : partitionStings) {
                Matcher matcher = pattern.matcher(partitionString);
                if (matcher.matches()) {
                    partitions.add(parsePartition(matcher.group(1)));
                }
            }

            LOGGER.log(Level.INFO, "found {0} partitions on {1}",
                    new Object[]{partitions.size(), device});
        }
        return partitions;
    }

    /**
     * returns how the EFI partition on the storage device can be upgraded
     *
     * @param neededEfiPartitionSize the needed size of the EFI partition
     * @return how the EFI partition on the storage device can be upgraded
     */
    public synchronized EfiUpgradeVariant getEfiUpgradeVariant(
            long neededEfiPartitionSize) {

        // lazy initialization of efiUpgradeVariant
        if (efiUpgradeVariant != null) {
            return efiUpgradeVariant;
        }

        getPartitions();

        if (efiPartition == null) {
            efiUpgradeVariant = EfiUpgradeVariant.REGULAR;
        } else {
            long missing = neededEfiPartitionSize - efiPartition.getSize();
            // Because of parted "optimal" alignments it might happen, that the
            // EFI partition created in the DLCopy installer is slightly smaller
            // than the exact size given to parted via command line.
            // Therefore we are a little bit more tolerant here...
            if (missing > 2097152 /* 2097152 = 2 MiB*/) {
                Partition nextPartition = partitions.get(
                        efiPartition.getNumber());
                // enlarging is (for now) only possible when the next partition
                // is ext[234]
                if (nextPartition.hasExtendedFilesystem()) {
                    efiUpgradeVariant = EfiUpgradeVariant.ENLARGE_REPARTITION;
                } else {
                    efiUpgradeVariant = EfiUpgradeVariant.ENLARGE_BACKUP;
                }
            } else {
                efiUpgradeVariant = EfiUpgradeVariant.REGULAR;
            }
        }

        LOGGER.log(Level.INFO, "efiUpgradeVariant of {0}: {1}",
                new Object[]{device, efiUpgradeVariant});

        return efiUpgradeVariant;
    }

    /**
     * returns if and how the system partition on the storage device can be
     * upgraded
     *
     * @param enlargedSystemSize the enlarged system size
     * @return if and how the storage device can be upgraded
     * @throws DBusException if a dbus exception occurs
     * @throws java.io.IOException if determining the size of /home and
     * /etc/cups fails
     */
    public synchronized SystemUpgradeVariant getSystemUpgradeVariant(
            long enlargedSystemSize) throws DBusException, IOException {

        // lazy initialization of systemUpgradeVariant
        if (systemUpgradeVariant != null) {
            return systemUpgradeVariant;
        }

        // !!! must be called before the next check, otherwise bootPartition
        // !!! could still be null even when there is one
        getPartitions();

        if (systemPartition == null) {
            noUpgradeReason = STRINGS.getString("No_System_Partition_Found");
            systemUpgradeVariant = SystemUpgradeVariant.IMPOSSIBLE;
            return systemUpgradeVariant;
        }

        if (dataPartition == null) {
            noUpgradeReason = STRINGS.getString("No_Data_Partition_Found");
            systemUpgradeVariant = SystemUpgradeVariant.IMPOSSIBLE;
            return systemUpgradeVariant;
        }

        // determine the size of the data to keep (/home and /etc/cups)
        MountInfo systemMountInfo = systemPartition.mount();
        List<String> readOnlyMountPoints = LernstickFileTools.mountAllSquashFS(
                systemMountInfo.getMountPath());
        systemUpgradeVariant = determineUpgradeVariant(
                readOnlyMountPoints, enlargedSystemSize);
        if (!systemMountInfo.alreadyMounted()) {
            systemPartition.umount();
        }

        return systemUpgradeVariant;
    }

    /**
     * returns the reason why this storage device can not be upgraded
     *
     * @return the noUpgradeReason
     */
    public String getNoUpgradeReason() {
        return noUpgradeReason;
    }

    /**
     * returns the EFI partition of this storage device
     *
     * @return the EFI partition of this storage device
     */
    public synchronized Partition getEfiPartition() {
        getPartitions();
        return efiPartition;
    }

    /**
     * returns the system partition of this storage device
     *
     * @return the system partition of this storage device
     */
    public synchronized Partition getSystemPartition() {
        getPartitions();
        return systemPartition;
    }

    /**
     * returns the data partition of this storage device
     *
     * @return the data partition of this storage device
     */
    public Partition getDataPartition() {
        getPartitions();
        return dataPartition;
    }

    /**
     * returns the exchange partition of this storage device
     *
     * @return the exchange partition of this storage device
     */
    public Partition getExchangePartition() {
        getPartitions();
        return exchangePartition;
    }

    /**
     * creates a primary partition on this storage device
     *
     * @param filesystemType the filesystem type of the partition
     * @param begin the begin of the primary partition (given in Byte)
     * @param end the end of the primary partition (given in Byte)
     * @throws IOException if an I/O exception occurs
     */
    public void createPrimaryPartition(String filesystemType,
            long begin, long end) throws IOException {

        /*
        When starting parted interactively and typing "help mkpart" it says:

        FS-TYPE is one of: zfs, btrfs, nilfs2, ext4, ext3, ext2, fat32, fat16,
        hfsx, hfs+, hfs, jfs, swsusp, linux-swap(v1), linux-swap(v0), ntfs,
        reiserfs, freebsd-ufs, hp-ufs, sun-ufs, xfs, apfs2, apfs1, asfs, amufs5,
        amufs4, amufs3, amufs2, amufs1, amufs0, amufs, affs7, affs6, affs5,
        affs4, affs3, affs2, affs1, affs0, linux-swap, linux-swap(new),
        linux-swap(old)
        
        We therefore map heresome known filesystem type IDs:
         */
        filesystemType = filesystemType.toLowerCase();
        switch (filesystemType) {
            case "vfat":
            case "exfat":
                filesystemType = "fat32";
                break;
            // TODO: add more if needed
        }

        ProcessExecutor processExecutor = new ProcessExecutor(true);
        int returnValue = processExecutor.executeProcess(true, true, "parted",
                "/dev/" + device, "mkpart", "primary", filesystemType,
                begin + "B", end + "B");
        if (returnValue != 0) {
            throw new IOException(
                    "creating partition on " + device + " failed");
        }
    }

    private SystemUpgradeVariant determineUpgradeVariant(
            List<String> readOnlyMountPoints, long enlargedSystemSize)
            throws DBusException, IOException {

        MountInfo dataMountInfo = dataPartition.mount();
        String dataMountPoint = dataMountInfo.getMountPath();

        File rwDir = null;
        File cowDir;
        if (Files.exists(Paths.get(dataMountPoint, "rw"))
                && Files.exists(Paths.get(dataMountPoint, "work"))) {
            // starting with Debian 9
            rwDir = LernstickFileTools.mountOverlay(
                    dataMountPoint, readOnlyMountPoints, true);
            cowDir = new File(rwDir, "merged");
        } else {
            // up to Debian 8
            cowDir = LernstickFileTools.mountAufs(
                    dataMountPoint, readOnlyMountPoints);
        }

        long homeSize = getDirectorySize(new File(cowDir, "home"));
        long cupsSize = getDirectorySize(new File(cowDir, "etc/cups"));
        long oldDataSize = homeSize + cupsSize;
        LOGGER.log(Level.INFO, "oldDataSize : {0}",
                LernstickFileTools.getDataVolumeString(oldDataSize, 1));
        // add a safety factor for file system overhead
        long oldDataSizeEnlarged = (long) (oldDataSize * 1.1);
        LOGGER.log(Level.INFO, "oldDataSizeEnlarged : {0}",
                LernstickFileTools.getDataVolumeString(
                        oldDataSizeEnlarged, 1));
        long dataPartitionSize = dataPartition.getSize();
        LOGGER.log(Level.INFO, "dataPartitionSize : {0}",
                LernstickFileTools.getDataVolumeString(dataPartitionSize, 1));
        // umount all temporary mountpoints
        LernstickFileTools.umount(cowDir.getPath());
        for (String readOnlyMountPoint : readOnlyMountPoints) {
            LernstickFileTools.umount(readOnlyMountPoint);
        }
        // remove temporary directories
        for (String readOnlyMountPoint : readOnlyMountPoints) {
            File tempDir = new File(readOnlyMountPoint).getParentFile();
            LOGGER.log(Level.INFO, "recursively deleting {0}", tempDir);
            LernstickFileTools.recursiveDelete(tempDir, true);
        }
        if (rwDir != null) {
            LernstickFileTools.recursiveDelete(rwDir, true);
        }
        if (!dataMountInfo.alreadyMounted()) {
            dataPartition.umount();
        }

        if (oldDataSizeEnlarged > dataPartitionSize) {
            noUpgradeReason = STRINGS.getString("Data_Partition_Too_Small");
            noUpgradeReason = MessageFormat.format(noUpgradeReason,
                    LernstickFileTools.getDataVolumeString(
                            oldDataSizeEnlarged, 1));
            systemUpgradeVariant = SystemUpgradeVariant.IMPOSSIBLE;
            return systemUpgradeVariant;
        }

        // check partitioning schema
        if (efiPartition == null) {
            // old partitioning schema without any efi partition
            setDestructiveUpgradeVariant();
            return systemUpgradeVariant;

        } else {
            switch (efiPartition.getNumber()) {
                case 1:
                    // fine, current partitioning schema
                    break;
                case 2:
                    if ((exchangePartition != null)
                            && (exchangePartition.getNumber() == 1)) {
                        // fine, we have the partitioning schema for
                        // older, *removable* USB flash drives:
                        //  1. partition: exchange
                        //  2. partition: boot
                        break;
                    } else {
                        // unknown partitioning schema
                        setDestructiveUpgradeVariant();
                        return systemUpgradeVariant;
                    }
                default:
                    // unknown partitioning schema
                    setDestructiveUpgradeVariant();
                    return systemUpgradeVariant;
            }
        }

        // Even though we already know the system partition we need to loop
        // here because we also need to know the previous partition to
        // decide if we can repartition the device.
        long remaining = -1;
        Partition previousPartition = null;
        for (Partition partition : partitions) {
            if (partition.isSystemPartition()) {
                long partitionSize = partition.getSize();
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.log(Level.INFO,
                            "enlargedSystemSize: {0}, size of {1}: {2}",
                            new Object[]{
                                enlargedSystemSize,
                                partition.getDeviceAndNumber(),
                                partitionSize
                            });
                }
                remaining = partitionSize - enlargedSystemSize;
                LOGGER.log(Level.FINE, "remaining = {0}", remaining);
                if (remaining >= 0) {
                    // the new system fits into the current system partition
                    systemUpgradeVariant = SystemUpgradeVariant.REGULAR;
                    return systemUpgradeVariant;
                }

                // The new system is larger than the current system
                // partition. Check if repartitioning is possible.
                //
                // TODO: more sophisticated checks
                //  - device with partition gaps
                //  - expand in both directions
                //  - ...
                if ((previousPartition != null)
                        && (!previousPartition.isExtended())) {
                    // right now we can only resize ext partitions
                    if (previousPartition.hasExtendedFilesystem()) {
                        long previousUsedSpace;
                        if (previousPartition.isPersistencePartition()) {
                            previousUsedSpace
                                    = previousPartition.getUsedSpace(true);
                        } else {
                            previousUsedSpace
                                    = previousPartition.getUsedSpace(false);
                        }

                        long usableSpace
                                = previousPartition.getSize()
                                - previousUsedSpace;
                        if (usableSpace > Math.abs(remaining)) {
                            systemUpgradeVariant
                                    = SystemUpgradeVariant.REPARTITION;
                            return systemUpgradeVariant;
                        }
                    }
                }

                // we already found the system partition
                // no need to search further
                break;
            }
            previousPartition = partition;
        }

        if (remaining < 0) {
            noUpgradeReason = STRINGS.getString("System_Partition_Too_Small");
            systemUpgradeVariant = SystemUpgradeVariant.IMPOSSIBLE;
        }

        return systemUpgradeVariant;
    }

    /**
     *
     * @param numberString the partition number as a string, e.g. "1" for sda1
     * or "p3" for nvme0n1p3
     * @return the partition
     */
    private Partition parsePartition(String numberString) {
        Partition partition = null;
        try {
            partition = Partition.getPartitionFromDevice(device, numberString);

            if ((dataPartition == null) && partition.isPersistencePartition()) {
                dataPartition = partition;
                LOGGER.log(Level.INFO, "dataPartition: {0}", dataPartition);

            } else if ((efiPartition == null) && partition.isEfiPartition()) {
                // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                // ! put the EFI partition check before the exchange     !
                // ! partition check because it is the more specific one !
                // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                efiPartition = partition;
                LOGGER.log(Level.INFO, "efiPartition: {0}", efiPartition);

            } else if ((exchangePartition == null)
                    && partition.isExchangePartition()) {
                exchangePartition = partition;
                LOGGER.log(Level.INFO,
                        "exchangePartition: {0}", exchangePartition);

            } else if ((systemPartition == null)
                    && partition.isSystemPartition()) {
                // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                // ! put the system partition check at the end of the list   !
                // ! because it is the most expensive one                    !
                // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                systemPartition = partition;
                LOGGER.log(Level.INFO, "systemPartition: {0}", systemPartition);
            }
        } catch (NumberFormatException | DBusException exception) {
            LOGGER.log(Level.WARNING, "", exception);
        }
        return partition;
    }

    private void setDestructiveUpgradeVariant() {
        if ((dataPartition == null) && (exchangePartition == null)) {
            systemUpgradeVariant = SystemUpgradeVariant.INSTALLATION;
        } else {
            systemUpgradeVariant = SystemUpgradeVariant.BACKUP;
        }
    }

    private long getDirectorySize(File directory) throws IOException {
        long directorySize = 0;
        if (directory.exists()) {
            directorySize = LernstickFileTools.getSize(directory.toPath());
        } else {
            LOGGER.log(Level.WARNING,
                    "{0} not found in data partition", directory);
        }
        LOGGER.log(Level.INFO, "size of {0}: {1}", new Object[]{
            directory,
            LernstickFileTools.getDataVolumeString(directorySize, 1)
        });
        return directorySize;
    }
}
