alias bk := build_ksud
alias bm := build_manager

build_ksud:
    cross build --target aarch64-linux-android --release

build_manager: build_ksud
    cp target/aarch64-linux-android/release/ksud manager/app/src/main/jniLibs/arm64-v8a/libksud.so
    cd manager && ./gradlew aDebug

build_ksud_watch:
    cargo ndk -t armeabi-v7a build --release --features watch-fd-broker
    mkdir -p manager/app/src/main/jniLibs/armeabi-v7a
    cp target/armv7-linux-androideabi/release/ksud manager/app/src/main/jniLibs/armeabi-v7a/libksud.so

build_manager_watch: build_ksud_watch
    cd manager && ./gradlew assembleDebug -PKSU_WATCH_ONLY=true

clippy:
    cargo fmt
    cross clippy --target aarch64-linux-android --release
