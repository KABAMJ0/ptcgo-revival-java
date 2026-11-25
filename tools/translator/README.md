# Card attack translator (milestone 1)

Generates Java attack registrations from the Python card scripts in the reference repo. Scope is the 15 batch-one
attacks already hand-ported in `src/main/java/com/ptcgo/revival/game/effects/BatchOneAttacks.java`. The hand port is
kept; the generated class is `GeneratedBatchOne.java`.

## Pipeline

1. `dump_ast.py` parses the card scripts named in a manifest with Python's `ast` module (read only) and writes a JSON
   IR: card header, attack title/damage/cost, the effect helper call with its literal arguments, and the abilityID
   (`uuid5(namespace, "<guid>:ability:<slot>")`, with the namespace read from `definitions.py`).
2. `emit_java.py` maps each IR effect call onto an existing `AttackEffects` factory and writes the Java class.

Only literal arguments are accepted: ints, strings, bools, None, negative ints, and `Enum.MEMBER` references
(`SpecialConditions.BURNED`). Anything else, an unknown helper, an unmodelled keyword, or a missing effect stops the
run with the file name and attack title. No output is written on failure.

Supported helpers: `recoil_attack`, `flip_damage` (only `coins` and `per_heads`), `flip_bonus`, `condition_attack`
(`flip`, `coins`, `min_heads`), `heal_dealt_attack`.

## Usage

From the repo root, with the Python checkout at `/path/to/ptcgo-revival`:

```sh
python3 tools/translator/dump_ast.py --src /path/to/ptcgo-revival \
    --manifest tools/translator/manifests/batch_one.json \
    --out tools/translator/ir/batch_one.json

python3 tools/translator/emit_java.py --ir tools/translator/ir/batch_one.json \
    --out src/main/java/com/ptcgo/revival/game/effects/GeneratedBatchOne.java
```

The IR is committed, so the Java side can be regenerated without the Python checkout. Re-running the dump is only
needed when a Python card script changes.

## Files

- `dump_ast.py`: Python AST to IR.
- `emit_java.py`: IR to `GeneratedBatchOne.java`.
- `manifests/batch_one.json`: which card files and attack titles to translate.
- `ir/batch_one.json`: the committed IR.

## Verification

`src/test/java/com/ptcgo/revival/game/effects/GeneratedBatchOneTest.java` runs every batch-one attack through both
the hand port and the generated registration, with the same scripted coin flips, and compares HP, Special
Conditions, poison counters, coin results and flips consumed. It also checks that every hand-port abilityID is
registered by the generated class. Mutating one generated line makes the test fail.

Run the suite with `mvn -q -B test`.

## Limits

- Milestone 1 covers the batch-one attacks only. Adding a card means adding its file and title to the manifest,
  then re-running both steps. Any helper outside the table fails loudly until an emitter mapping exists.
- `damage_operator` and `game_text` are read but not emitted. Printed damage is not emitted either: the factories
  read it from the card's ability entry at runtime, as the hand port does. The IR `damage` is informational.
- The emitter's Python default values (`coins=1`, `min_heads=1`) are applied in `emit_java.py`, mirroring
  `attacks_common.py`.
