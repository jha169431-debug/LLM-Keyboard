#!/usr/bin/env bash
set -e

THEME="${1:-}"

case "$THEME" in
    pearl|ice|warm|smoke)
        ;;
    *)
        echo "Usage: $0 {pearl|ice|warm|smoke}"
        exit 1
        ;;
esac

cp \
  "theme-presets/${THEME}.xml" \
  "app/src/main/res/values/keyboard_theme_colors.xml"

echo "Keyboard theme switched to: $THEME"
