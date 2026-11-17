// Where the popup sits. The plugin writes this file into the pack from config.yml (popup.scale, popup.side,
// popup.margin-x, popup.margin-top); the values here are the defaults, used when the pack is built by hand.
const float RADIO_HUD_SCALE = 1.0;              // 1 = one popup pixel is one GUI pixel
const float RADIO_HUD_LEFT = 0.0;               // 0 = right edge of the screen, 1 = left edge
const vec2  RADIO_HUD_MARGIN = vec2(4.0, 4.0);  // distance from the side edge and from the top, GUI pixels
