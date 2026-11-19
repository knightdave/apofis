{
  description = "Apofis - Android dev shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
        config.android_sdk.accept_license = true;
      };
      buildToolsVersion = "37.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.2" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ pkgs.jdk21 android.androidsdk ];
        JAVA_HOME = pkgs.jdk21.home;
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        # The aapt2 AGP downloads from Maven is dynamically linked and does not run on NixOS.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
      };
    };
}
