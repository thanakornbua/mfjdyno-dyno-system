# On dev machine
cd ~/dyno-system
cargo build --release --target aarch64-unknown-linux-gnu
cd java && ./gradlew clean build

scp ../target/aarch64-unknown-linux-gnu/release/dynod mfjdyno@100.103.42.41:/tmp/dynod
scp build/libs/dyno-ui.jar mfjdyno@100.103.42.41:/tmp/dyno-operator-console.jar

ssh mfjdyno@100.103.42.41 "
  sudo systemctl stop dynod dyno-operator-console &&
  sudo cp /tmp/dynod /usr/local/bin/dynod &&
  sudo cp /tmp/dyno-operator-console.jar /opt/dyno-operator-console/dyno-operator-console.jar &&
  sudo systemctl start dynod &&
  sleep 2 &&
  sudo systemctl start dyno-operator-console
"
