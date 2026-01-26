# How to Deploy to NixOS

Deploy radiale to a NixOS server as a systemd service.

## Problem

You want to run radiale on a NixOS server with automatic startup, logging, and service management.

## Prerequisites

- A NixOS server accessible via SSH
- Nix flakes enabled
- Your radiale configuration ready
- Basic familiarity with NixOS configuration
- Avahi/mDNS enabled (required for ESPHome and Chromecast discovery)

## Steps

### 1. Add Radiale to Your Flake Inputs

In your NixOS flake (`flake.nix`):

```nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    
    radiale-src = {
      url = "github:xlfe/radiale";
      flake = false;
    };
  };
  
  outputs = { self, nixpkgs, radiale-src, ... }: {
    # ... your configuration
  };
}
```

### 2. Create the Radiale Package

In your flake outputs or a separate module:

```nix
{ pkgs, radiale-src, ... }:

let
  python = pkgs.python312;
  
  radialePkg = python.pkgs.buildPythonPackage {
    pname = "radiale";
    version = "0.5.5";
    src = radiale-src;
    format = "setuptools";
    
    propagatedBuildInputs = with python.pkgs; [
      aioesphomeapi
      websockets
      aiohttp
      zeroconf
      aiomqtt
      astral
      bcoding
      protobuf
    ];
    
    doCheck = false;
  };
  
in {
  # Package available as radialePkg
}
```

### 3. Enable Avahi for mDNS Discovery

Radiale uses mDNS to discover ESPHome and Chromecast devices. Add to your NixOS configuration:

```nix
{
  # Enable Avahi for mDNS
  services.avahi = {
    enable = true;
    nssmdns4 = true;  # Enable .local resolution
    publish = {
      enable = true;
      addresses = true;
    };
  };
  
  # Open mDNS port in firewall
  networking.firewall.allowedUDPPorts = [ 5353 ];
}
```

### 4. Create a Systemd Service

Add to your NixOS configuration:

```nix
{ config, pkgs, ... }:

{
  systemd.services.radiale = {
    description = "Radiale Home Automation";
    wantedBy = [ "multi-user.target" ];
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    
    serviceConfig = {
      Type = "simple";
      User = "radiale";
      Group = "radiale";
      WorkingDirectory = "/var/lib/radiale";
      ExecStart = "${pkgs.clojure}/bin/clojure -i config/setup.clj";
      Restart = "always";
      RestartSec = 10;
      
      # Security hardening
      NoNewPrivileges = true;
      ProtectSystem = "strict";
      ProtectHome = true;
      ReadWritePaths = [ "/var/lib/radiale" ];
    };
    
    environment = {
      PYTHONPATH = "${radialePkg}/${python.sitePackages}";
    };
  };
  
  users.users.radiale = {
    isSystemUser = true;
    group = "radiale";
    home = "/var/lib/radiale";
    createHome = true;
  };
  
  users.groups.radiale = {};
}
```

### 5. Deploy Your Configuration

Copy your radiale config to the server:

```bash
# Create config directory
ssh user@server "sudo mkdir -p /var/lib/radiale/config"

# Copy configuration
scp config/setup.clj user@server:/tmp/
ssh user@server "sudo mv /tmp/setup.clj /var/lib/radiale/config/"
ssh user@server "sudo chown -R radiale:radiale /var/lib/radiale"
```

### 6. Apply NixOS Configuration

```bash
# From your dotfiles directory
nixos-rebuild switch --flake .#your-host --target-host user@server
```

### 7. Verify the Service

```bash
# Check service status
ssh user@server "systemctl status radiale"

# View logs
ssh user@server "journalctl -u radiale -f"
```

## Alternative: Development Shell

For development or testing, use a nix shell instead:

```nix
# In your flake.nix outputs
devShells.x86_64-linux.radiale = pkgs.mkShell {
  packages = [
    pkgs.clojure
    pkgs.jdk17
    (python.withPackages (ps: [ radialePkg ]))
  ];
};
```

Then:
```bash
nix develop .#radiale
cd /path/to/radiale
./start.sh
```

## Troubleshooting

### Service fails to start

Check logs for details:
```bash
journalctl -u radiale -n 50 --no-pager
```

Common issues:
- Missing Python dependencies in the package
- Configuration file not found
- Permission issues on `/var/lib/radiale`

### Python import errors

Ensure `PYTHONPATH` includes the radiale package:
```bash
ssh user@server "systemctl show radiale | grep PYTHONPATH"
```

### Network/device access issues

The service runs with restricted permissions. You may need to:
- Add the user to specific groups (e.g., `dialout` for serial)
- Adjust `ReadWritePaths` for additional directories
- Use `AmbientCapabilities` for network capabilities

### Devices not discovered (mDNS issues)

If ESPHome or Chromecast devices aren't being discovered:

1. Verify Avahi is running:
   ```bash
   systemctl status avahi-daemon
   ```

2. Test mDNS resolution:
   ```bash
   avahi-browse -art | grep -E "esphome|googlecast"
   ```

3. Check firewall allows mDNS (UDP port 5353):
   ```bash
   sudo iptables -L -n | grep 5353
   ```

4. Ensure devices are on the same network/VLAN as the server

## See Also

- [Getting Started](../tutorials/getting-started.md) - Local development setup
- [Run Tests](run-tests.md) - Verify changes before deploying
- [Architecture](../explanation/architecture.md) - System design overview
