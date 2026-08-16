#!/usr/bin/env python3
"""
Fit the per-option scoring multipliers.

The stats a slot offers are not comparable in raw form: a quarterback throws for 220 yards
in the same week a receiver catches four passes. Each option therefore needs a multiplier
that puts it on a shared scale.

Setting each option to the same *average* points is not enough. An option with a wider
spread across players wins more often even when the means match, so the fix is to solve
directly for the thing that matters: within a slot, each option should be the best pick for
roughly an equal share of players. Anything more lopsided and the choice is decorative.

Run once a season, or after changing which stats a slot offers, then paste the emitted
block into application.properties.

    python3 tools/calibrate.py [season] [week]
"""

import collections
import json
import statistics
import subprocess
import sys

SEASON = sys.argv[1] if len(sys.argv) > 1 else "2026"
WEEK = sys.argv[2] if len(sys.argv) > 2 else "1"

# slot -> (sleeper positions, points floor identifying a starter, [(option key, sleeper stat)])
SLOTS = {
    "qb": (["QB"], 8.0, [("arm", "pass_yd"), ("legs", "rush_yd"), ("shoulders", "pass_td")]),
    "rb": (["RB"], 6.0, [("legs", "rush_yd"), ("hands", "rec"), ("chest", "rec_yd")]),
    "flex": (["WR", "TE"], 5.0, [("chest", "rec_yd"), ("hands", "rec"), ("feet", "rec_td")]),
}


def fetch(position):
    """Sleeper rejects urllib's default user agent, so shell out to curl."""
    url = (f"https://api.sleeper.com/projections/nfl/{SEASON}/{WEEK}"
           f"?season_type=regular&position[]={position}&order_by=ppr")
    return json.loads(subprocess.run(["curl", "-s", url], capture_output=True, text=True).stdout)


def starters(positions, floor):
    out = []
    for position in positions:
        for row in fetch(position):
            stats = row.get("stats") or {}
            if stats.get("pts_ppr", 0) > floor:
                out.append(stats)
    return out


def win_share(players, options, multipliers):
    wins = collections.Counter()
    for stats in players:
        wins[max(options, key=lambda o: stats.get(o[1], 0) * multipliers[o[1]])[0]] += 1
    return {key: wins[key] / len(players) for key, _ in options}


def solve(players, options, iterations=4000, step=0.01):
    """Nudge whichever option is furthest from an even share, repeatedly."""
    multipliers = {
        stat: 10.0 / statistics.mean([p.get(stat, 0) for p in players])
        for _, stat in options
    }
    target = 1 / len(options)
    for _ in range(iterations):
        shares = win_share(players, options, multipliers)
        worst = max(options, key=lambda o: abs(shares[o[0]] - target))
        multipliers[worst[1]] *= (1 - step) if shares[worst[0]] > target else (1 + step)
    return multipliers, win_share(players, options, multipliers)


print(f"# calibrated against sleeper projections, {SEASON} week {WEEK}")
for slot, (positions, floor, options) in SLOTS.items():
    players = starters(positions, floor)
    multipliers, shares = solve(players, options)
    print(f"# {slot}: {len(players)} starters | "
          + ", ".join(f"{k} wins {100 * shares[k]:.0f}%" for k, _ in options))
    for key, stat in options:
        print(f"wheelhouse.scoring.multipliers[{slot}.{key}]={multipliers[stat]:.4f}")
