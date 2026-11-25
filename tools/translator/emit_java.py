#!/usr/bin/env python3
"""Emit a Java registration class from the dump_ast IR.

Each IR effect call is mapped onto an existing `AttackEffects` factory. The mapping is explicit: an unknown helper
name, an unmodelled keyword, a non-default option the factory cannot express, or a missing effect is a hard error
that names the card file and attack. Nothing is silently dropped.

Usage:
    python3 tools/translator/emit_java.py --ir tools/translator/ir/batch_one.json \
        --out src/main/java/com/ptcgo/revival/game/effects/GeneratedBatchOne.java
"""

import argparse
import json
import sys

PACKAGE = "com.ptcgo.revival.game.effects"
CLASS_NAME = "GeneratedBatchOne"

# Mirrors ptcgo/game/attributes.py CLIENT_SPECIAL_CONDITION_NAMES (the client-side names the Java port sends).
CONDITION_NAMES = {
    "SpecialConditions": {
        "ASLEEP": "Asleep",
        "BURNED": "Burned",
        "CONFUSED": "Confused",
        "PARALYZED": "Paralyzed",
        "POISONED": "Poisoned",
    },
}


class EmitError(Exception):
    """An IR effect the emitter does not know how to translate."""


def java_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def check_args(where, name, args, kwargs, *, positional, allowed):
    if len(args) != positional:
        raise EmitError(f"{where}: {name} takes {positional} positional argument(s), got {len(args)}")
    unknown = set(kwargs) - set(allowed)
    if unknown:
        raise EmitError(f"{where}: {name} keyword(s) {sorted(unknown)} are not modelled by the emitter")


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def condition_name(where, arg):
    if not (isinstance(arg, dict) and arg.get("enum") in CONDITION_NAMES):
        raise EmitError(f"{where}: condition_attack argument {arg!r} is not a known SpecialConditions member")
    names = CONDITION_NAMES[arg["enum"]]
    if arg["member"] not in names:
        raise EmitError(f"{where}: SpecialConditions.{arg['member']} has no client name in the emitter table")
    return java_string(names[arg["member"]])


def emit_recoil(where, args, kwargs):
    # Python recoil_attack(self_damage, damage=None, also=None) -> AttackEffects.recoil(self_damage)
    check_args(where, "recoil_attack", args, kwargs, positional=1, allowed=())
    if not is_int(args[0]):
        raise EmitError(f"{where}: recoil_attack self_damage must be an int")
    return f"AttackEffects.recoil({args[0]})"


def emit_flip_damage(where, args, kwargs):
    # Only the flip_damage(coins=N, per_heads=M) shape maps to flipPerHeads. Base, bonus, require_all, until_tails
    # and the rest need factories that do not exist yet, so they are rejected rather than approximated.
    check_args(where, "flip_damage", args, kwargs, positional=0, allowed=("coins", "per_heads"))
    if "per_heads" not in kwargs:
        raise EmitError(f"{where}: flip_damage without per_heads is not modelled (no AttackEffects factory)")
    coins = kwargs.get("coins", 1)
    per = kwargs["per_heads"]
    if not (is_int(coins) and is_int(per)):
        raise EmitError(f"{where}: flip_damage coins/per_heads must be ints")
    return f"AttackEffects.flipPerHeads({coins}, {per})"


def emit_flip_bonus(where, args, kwargs):
    # Python flip_bonus(bonus, coins=1) -> flip_damage(coins, bonus, require_all=True)
    check_args(where, "flip_bonus", args, kwargs, positional=1, allowed=("coins",))
    coins = kwargs.get("coins", 1)
    if not (is_int(args[0]) and is_int(coins)):
        raise EmitError(f"{where}: flip_bonus bonus/coins must be ints")
    return f"AttackEffects.flipBonus({args[0]}, {coins})"


def emit_condition_attack(where, args, kwargs):
    # Python condition_attack(*conditions, flip=False, coins=1, min_heads=1). Without flip the coin fields are
    # unused, and the Java factory takes (false, 0, 0) for that case, matching the hand port.
    check_args(where, "condition_attack", args, kwargs, positional=len(args), allowed=("flip", "coins", "min_heads"))
    if not args:
        raise EmitError(f"{where}: condition_attack needs at least one condition")
    names = [condition_name(where, a) for a in args]
    flip = kwargs.get("flip", False)
    if not isinstance(flip, bool):
        raise EmitError(f"{where}: condition_attack flip must be a bool")
    if flip:
        coins = kwargs.get("coins", 1)
        min_heads = kwargs.get("min_heads", 1)
        if not (is_int(coins) and is_int(min_heads)):
            raise EmitError(f"{where}: condition_attack coins/min_heads must be ints")
        head = f"true, {coins}, {min_heads}"
    else:
        if "coins" in kwargs or "min_heads" in kwargs:
            raise EmitError(f"{where}: condition_attack coins/min_heads given without flip=True")
        head = "false, 0, 0"
    return f"AttackEffects.condition({head}, {', '.join(names)})"


def emit_heal_dealt_attack(where, args, kwargs):
    check_args(where, "heal_dealt_attack", args, kwargs, positional=0, allowed=())
    return "AttackEffects.healDealt()"


EMITTERS = {
    "recoil_attack": emit_recoil,
    "flip_damage": emit_flip_damage,
    "flip_bonus": emit_flip_bonus,
    "condition_attack": emit_condition_attack,
    "heal_dealt_attack": emit_heal_dealt_attack,
}


def describe_call(effect):
    parts = [repr(a) if not isinstance(a, dict) else f"{a['enum']}.{a['member']}" for a in effect["args"]]
    parts += [f"{k}={v!r}" for k, v in effect["kwargs"].items()]
    return f"{effect['name']}({', '.join(parts)})"


def emit_attack(card, attack):
    where = f"{card['file']} attack {attack['title']!r}"
    effect = attack.get("effect")
    if effect is None:
        raise EmitError(f"{where}: no effect; batch attacks must name one")
    emitter = EMITTERS.get(effect["name"])
    if emitter is None:
        raise EmitError(f"{where}: unknown effect helper {effect['name']!r} (no emitter mapping)")
    expr = emitter(where, effect["args"], effect["kwargs"])
    comment = (f"// {card['set_code']} {card['display_name']} {card['collector_number']} "
               f"\"{attack['title']}\" {attack['damage']}: {describe_call(effect)}")
    line = f"        registry.attack({java_string(attack['ability_id'])}, " \
           f"EffectRegistry.AttackSpec.of({expr}));"
    return comment, line


def render(ir, ir_path):
    out = [
        f"package {PACKAGE};",
        "",
        "/**",
        " * GENERATED by tools/translator/emit_java.py from " + ir_path.replace("\\", "/") + ".",
        " * Do not edit by hand: change the emitter or the Python source, then regenerate.",
        " * Registers the batch-one attacks the same way {@link BatchOneAttacks} does, from the Python card scripts.",
        " */",
        f"public final class {CLASS_NAME} {{",
        f"    private {CLASS_NAME}() {{}}",
        "",
        "    /** Registers the generated batch-one attacks on {@code registry}. */",
        "    public static EffectRegistry register(EffectRegistry registry) {",
    ]
    count = 0
    for card in ir["cards"]:
        for attack in card["attacks"]:
            comment, line = emit_attack(card, attack)
            out.append("        " + comment)
            out.append(line)
            count += 1
    out += ["        return registry;", "    }", "}", ""]
    return "\n".join(out), count


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ir", required=True, help="IR JSON written by dump_ast.py")
    p.add_argument("--out", required=True, help="Java file to write")
    args = p.parse_args(argv)
    with open(args.ir, encoding="utf-8") as fh:
        ir = json.load(fh)
    if ir.get("source_format") != 1:
        print(f"emit_java: unsupported IR format {ir.get('source_format')!r}", file=sys.stderr)
        return 1
    try:
        text, count = render(ir, args.ir)
    except EmitError as exc:
        print(f"emit_java: {exc}", file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(text)
    print(f"emit_java: wrote {count} attacks to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
