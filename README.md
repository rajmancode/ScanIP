# ScanIP

An Android network scanner in the spirit of Advanced IP Scanner. It sweeps your Wi-Fi or Ethernet
subnet and lists every device it finds, with names, manufacturer, MAC (where Android allows it)
and running services.

## Features

- **Auto-detected range.** Reads your IP, prefix, gateway and DNS from the active Wi-Fi or Ethernet
  connection, ignoring cellular and VPN. You can also enter ranges by hand: `192.168.1.1-254`,
  `10.0.0.0/24`, `192.168.1.10-192.168.1.50`, or comma-separated lists. A scan covers up to 4096 hosts.
- **Host discovery** using ICMP ping (the system `ping` binary, no root needed) with a fallback to TCP probes.
  A refused TCP connection still counts as "up", so firewalled hosts are found too.
- **Names** come from reverse DNS (asks your router directly), NetBIOS node status (Windows and Samba
  names, workgroup and MAC), unicast mDNS/Bonjour (Apple, Linux, Android) and UPnP/SSDP (friendly name,
  manufacturer, model, serial).
- **Manufacturer lookup** uses the IEEE OUI registry, downloaded once from the menu. Randomised
  private MACs are labelled as such.
- **Services.** A quick check during the sweep powers the HTTP, HTTPS, SSH, FTP, SMB, RDP, VNC and UPnP badges.
  The detail screen can scan about 70 common ports or a custom list. It grabs banners and page titles from HTTP,
  SSH and FTP.
- **Actions:** open a device's web UI in the browser, send Wake-on-LAN, copy the IP, ping again.
- **Export:** share all results as CSV.
- Filter and search, gateway and own-device markers, and Material 3 with dynamic colour and dark mode.

## Android limitations

From Android 10 onward, apps can't read the ARP table (`/proc/net/arp`, `ip neigh`), and Android
reports the phone's own MAC as hidden. ScanIP still shows a MAC (and so the vendor) when the device
reports it through NetBIOS, or on older Android versions where the ARP table is readable.
Desktop tools can always show MACs because they read the ARP cache directly.

## Building

Requirements: JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Every push runs the **Android build** GitHub Actions workflow. It runs the unit tests and uploads
the debug and release APKs as a downloadable `ScanIP-apk` artifact.
The release APK is signed with the debug key so it installs directly. Set up your own signing config before publishing it.

## Project layout

```
app/src/main/java/com/scanip/app/
  MainActivity.kt          entry point
  ScanViewModel.kt         scan orchestration and state
  model/Device.kt          device, UPnP, port and network models
  net/                     IpUtils, NetworkInfoProvider, HostProber, NameResolver (DNS/NetBIOS/mDNS),
                           UpnpDiscovery, PortScanner, ArpTable, OuiDatabase, WakeOnLan
  ui/                      Compose screens (scan list and device detail)
```

Only scan networks you own or have permission to scan.
