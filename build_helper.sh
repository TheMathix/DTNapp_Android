set NDK_HOME=D:\Users\math_\AppData\Local\Android\Sdk\ndk\25.2.9519653 
set PATH=%NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin;%PATH% 
set CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android21-clang.cmd
cargo build --target aarch64-linux-android --release