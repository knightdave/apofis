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
      # SDK pins come from the version catalog, shared with Gradle and CI.
      v = (builtins.fromTOML (builtins.readFile ./gradle/libs.versions.toml)).versions;
      buildToolsVersion = v.buildTools;
      compose = extra: pkgs.androidenv.composeAndroidPackages ({
        platformVersions = [ "${v.compileSdk}.${v.compileSdkMinor}" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      } // extra);
      # API 37.0 is the newest level with a plain AOSP ("default") x86_64 image: no Google services needed.
      e2eImageApi = "37.0";
      shell = android: let sdk = "${android.androidsdk}/libexec/android-sdk"; in pkgs.mkShell {
        packages = [ pkgs.jdk21 android.androidsdk ];
        JAVA_HOME = pkgs.jdk21.home;
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        # The aapt2 AGP downloads from Maven is dynamically linked and does not run on NixOS.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
      };
    in
    {
      devShells.${system} = {
        default = shell (compose { });
        # Emulator host shell for tools/emulator/run-e2e.sh.
        emulator = shell (compose {
          platformVersions = [ "${v.compileSdk}.${v.compileSdkMinor}" e2eImageApi ];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "default" ];
          abiVersions = [ "x86_64" ];
        });
      };
    };
}
