{
  description = "Reproduceable dev environment";

  inputs = {
    nixpkgs.url      = "github:NixOS/nixpkgs/nixos-unstable";
    rust-overlay.url = "github:oxalica/rust-overlay";
    flake-utils.url  = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, rust-overlay, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        overlays = [ (import rust-overlay) ];
        pkgs = import nixpkgs {
          inherit system overlays;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };
      in
      with pkgs;
      {
        devShells = {
          default = mkShell {
            buildInputs = [
              (pkgs.rust-bin.stable."1.71.1".default.override {
                extensions = [ "rust-src" ];
                targets = [
                  "x86_64-linux-android"
                  "aarch64-linux-android"
                ];
              })
            ];
          };
        };
      }
    );
}
