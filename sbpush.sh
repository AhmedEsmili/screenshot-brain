#!/data/data/com.termux/files/usr/bin/bash
# Usage:  bash sbpush.sh install   (once)   then just:  sbpush
main() {
  REPO="$HOME/screenshot-brain"
  if [ "$1" = "install" ]; then
    printf '#!/data/data/com.termux/files/usr/bin/bash\nexec bash "$HOME/screenshot-brain/sbpush.sh" "$@"\n' > "$PREFIX/bin/sbpush"
    chmod +x "$PREFIX/bin/sbpush"
    git config --global credential.helper store
    echo "Installed. From now on just type: sbpush"
    return 0
  fi
  ZIP=$(find "$HOME/storage/downloads" -maxdepth 1 -name 'screenshot-brain*.zip' -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)
  if [ -z "$ZIP" ]; then echo "No screenshot-brain zip found in Downloads."; return 1; fi
  echo "Using: $ZIP"
  cd "$HOME" && unzip -oq "$ZIP"
  cd "$REPO"
  git add -A
  if git diff --cached --quiet; then echo "No changes to push."; return 0; fi
  git commit -qm "update $(date +%F_%H%M)"
  git push
  rm -f "$ZIP"
  URL=$(git remote get-url origin | sed -E 's#https://[^@]*@#https://#; s#\.git$##')
  echo "Pushed. Build progress: $URL/actions"
  echo "APK link (after ~6 min): $URL/releases/latest/download/ScreenshotBrain.apk"
}
main "$@"
exit
