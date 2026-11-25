#!/usr/bin/env python3
"""Dump selected Python card attacks to a JSON IR for the Java translator.

Reads card scripts from a Python checkout (read only) with the `ast` module. Only the cards and attack titles named
in the manifest are parsed further; everything else in a card file is ignored. Only literal arguments are accepted
(int, str, bool, None, negative int, and `Enum.MEMBER` references such as `SpecialConditions.BURNED`). Anything
else fails loudly with the file name.

Usage:
    python3 tools/translator/dump_ast.py --src /path/to/ptcgo-revival \
        --manifest tools/translator/manifests/batch_one.json --out tools/translator/ir/batch_one.json
"""

import argparse
import ast
import json
import os
import sys
import uuid

CARDS_DIR = os.path.join("ptcgo", "game", "scripts", "cards")
ID_SOURCE = os.path.join("ptcgo", "game", "content", "definitions.py")
ATTACK_KEYWORDS = {"title", "damage", "damage_operator", "game_text", "cost", "effect"}


class TranslationError(Exception):
    """A card script uses something the translator does not support."""


def literal(node, where):
    """Converts a literal AST node to a JSON-able value. Enum members become {"enum", "member"}."""
    if isinstance(node, ast.Constant):
        if node.value is None or isinstance(node.value, (bool, int, str)):
            return node.value
        raise TranslationError(f"{where}: unsupported literal {node.value!r}")
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub) \
            and isinstance(node.operand, ast.Constant) and isinstance(node.operand.value, int) \
            and not isinstance(node.operand.value, bool):
        return -node.operand.value
    if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
        return {"enum": node.value.id, "member": node.attr}
    raise TranslationError(f"{where}: non-literal argument ({ast.dump(node)[:80]})")


def call_args(call, where):
    args = [literal(a, where) for a in call.args]
    kwargs = {}
    for kw in call.keywords:
        if kw.arg is None:
            raise TranslationError(f"{where}: **kwargs are not supported")
        kwargs[kw.arg] = literal(kw.value, where)
    return args, kwargs


def module_assign(tree, name):
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets):
            return node.value
    return None


def ability_namespace(src):
    path = os.path.join(src, ID_SOURCE)
    with open(path, encoding="utf-8") as fh:
        tree = ast.parse(fh.read(), path)
    value = module_assign(tree, "_ABILITY_ID_NAMESPACE")
    if not (isinstance(value, ast.Call) and value.args and isinstance(value.args[0], ast.Constant)):
        raise TranslationError(f"{ID_SOURCE}: cannot read _ABILITY_ID_NAMESPACE literal")
    return uuid.UUID(value.args[0].value)


def ability_id(namespace, guid, slot):
    """Mirror of ptcgo.game.content.definitions.ability_id_for: uuid5(ns, "<guid>:ability:<slot>")."""
    return str(uuid.uuid5(namespace, f"{guid}:ability:{slot}"))


def parse_card(src, rel):
    path = os.path.join(src, CARDS_DIR, rel)
    with open(path, encoding="utf-8") as fh:
        tree = ast.parse(fh.read(), path)
    card_call = module_assign(tree, "card")
    if not (isinstance(card_call, ast.Call) and getattr(card_call.func, "id", None) == "PokemonCardDef"):
        raise TranslationError(f"{rel}: no `card = PokemonCardDef(...)` at module level")
    if card_call.args:
        raise TranslationError(f"{rel}: PokemonCardDef takes keyword arguments only")
    header = {kw.arg: literal(kw.value, rel) for kw in card_call.keywords
              if kw.arg in ("guid", "set_code", "collector_number", "display_name")}
    for key in ("guid", "set_code", "collector_number", "display_name"):
        if key not in header:
            raise TranslationError(f"{rel}: PokemonCardDef is missing `{key}`")
    abilities_node = next((kw.value for kw in card_call.keywords if kw.arg == "abilities"), None)
    if not isinstance(abilities_node, ast.List):
        raise TranslationError(f"{rel}: `abilities` must be a list literal")
    return header, abilities_node


def parse_attack(node, rel, slot):
    if not (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Attack"):
        raise TranslationError(f"{rel}: abilities[{slot}] is not an Attack(...) call")
    where = f"{rel}: abilities[{slot}]"
    if node.args:
        raise TranslationError(f"{where}: Attack takes keyword arguments only")
    unknown = {kw.arg for kw in node.keywords} - ATTACK_KEYWORDS
    if unknown:
        raise TranslationError(f"{where}: unsupported Attack keyword(s) {sorted(unknown)}")
    # cost and effect are structured (dict / helper call) and are read below; scalars go through literal().
    kwargs = {kw.arg: literal(kw.value, where) for kw in node.keywords if kw.arg in ("title", "damage",
                                                                                     "damage_operator", "game_text")}
    if "title" not in kwargs:
        raise TranslationError(f"{where}: Attack has no title")
    effect = None
    effect_node = next((kw.value for kw in node.keywords if kw.arg == "effect"), None)
    if effect_node is not None:
        if not (isinstance(effect_node, ast.Call) and isinstance(effect_node.func, ast.Name)):
            name = getattr(effect_node, "id", ast.dump(effect_node)[:60])
            raise TranslationError(f"{where}: effect must be a helper call, got reference {name!r}")
        eargs, ekwargs = call_args(effect_node, where)
        effect = {"name": effect_node.func.id, "args": eargs, "kwargs": ekwargs}
    cost = []
    cost_node = next((kw.value for kw in node.keywords if kw.arg == "cost"), None)
    if isinstance(cost_node, ast.Dict):
        for k, v in zip(cost_node.keys, cost_node.values):
            key = literal(k, where)
            if not isinstance(key, dict):
                raise TranslationError(f"{where}: cost keys must be enum members")
            cost.append({**key, "count": literal(v, where)})
    return {
        "slot": slot,
        "title": kwargs["title"],
        "damage": kwargs.get("damage", 0),
        "damage_operator": kwargs.get("damage_operator", ""),
        "game_text": kwargs.get("game_text", ""),
        "cost": cost,
        "effect": effect,
    }


def dump(src, manifest_path):
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    namespace = ability_namespace(src)
    cards = []
    for entry in manifest["cards"]:
        rel = entry["file"]
        header, abilities_node = parse_card(src, rel)
        wanted = list(entry["attacks"])
        found = {}
        for slot, node in enumerate(abilities_node.elts):
            if not (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Attack"):
                continue
            title = next((literal(kw.value, rel) for kw in node.keywords if kw.arg == "title"), None)
            if title in wanted:
                if title in found:
                    raise TranslationError(f"{rel}: attack {title!r} appears twice")
                found[title] = parse_attack(node, rel, slot)
                found[title]["ability_id"] = ability_id(namespace, header["guid"], slot)
        missing = [t for t in wanted if t not in found]
        if missing:
            raise TranslationError(f"{rel}: attack(s) {missing} not found in abilities")
        cards.append({
            "file": rel,
            "guid": header["guid"],
            "set_code": header["set_code"],
            "collector_number": header["collector_number"],
            "display_name": header["display_name"],
            "attacks": [found[t] for t in wanted],
        })
    return {"source_format": 1, "cards": cards}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--src", required=True, help="path to the Python ptcgo-revival checkout (read only)")
    p.add_argument("--manifest", required=True, help="JSON manifest listing card files and attack titles")
    p.add_argument("--out", required=True, help="where to write the IR JSON")
    args = p.parse_args(argv)
    try:
        ir = dump(args.src, args.manifest)
    except TranslationError as exc:
        print(f"dump_ast: {exc}", file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(ir, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    total = sum(len(c["attacks"]) for c in ir["cards"])
    print(f"dump_ast: wrote {total} attacks from {len(ir['cards'])} cards to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
