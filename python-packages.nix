# Minimal Python package overlay for radiale
# Only adds packages not available in nixpkgs

{ pkgs, fetchurl, fetchgit, fetchhg }:

self: super: {
  # bcoding - streaming bencode library required for babashka pod protocol
  # Not available in nixpkgs
  "bcoding" = super.buildPythonPackage rec {
    pname = "bcoding";
    version = "1.5";
    src = fetchurl {
      url = "https://files.pythonhosted.org/packages/9d/2f/6ee83fc7ec45bff8b1cf54f45acb61b00dd6ffacd68639e35fc247ae9f21/bcoding-1.5-py2.py3-none-any.whl";
      sha256 = "02y11vf55zq555m9p8qpa0a9jvwjsb0prywxd1m07kxi4v2mwm5k";
    };
    format = "wheel";
    doCheck = false;
  };
}
