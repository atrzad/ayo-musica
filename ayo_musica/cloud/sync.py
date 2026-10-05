"""Data sync with the account (the same rules as the phone's): what changed here goes up, what changed elsewhere
comes down, the newest change wins on the server.

The desktop does not have everything the phone has (corrections live in the files' tags here, there is no shuffle
blacklist...). So it only touches the records it *manages*, and when it changes one it keeps the fields it does
not know (a like made here does not erase the phone's "fora do aleatório")."""
import json
import os

from . import keys as songkeys


def dumps(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def record_id(kind, key):
    return f"{kind}\u0000{key}"


class SyncEngine:
    def __init__(self, state_file, hooks, device, transport):
        """hooks: songs() → [(item, key, in_cloud)], export(matcher) → {id: value}, apply(kind, key, value, matcher)
        → bool, manages(kind, key) → bool. transport(since, changes) → server answer."""
        self.state_file = state_file
        self.hooks = hooks
        self.device = device
        self.transport = transport
        self.state = self._read()

    def _read(self):
        try:
            with open(self.state_file, encoding="utf-8") as handle:
                state = json.load(handle)
        except (OSError, ValueError):
            state = {}
        return {"server": state.get("server", ""), "email": state.get("email", ""), "since": state.get("since", 0),
                "base": state.get("base", {}), "held": state.get("held", {}), "remote_plays": state.get("remote_plays", {})}

    def _save(self):
        os.makedirs(os.path.dirname(self.state_file), exist_ok=True)
        temp = f"{self.state_file}.tmp"
        with open(temp, "w", encoding="utf-8") as handle:
            json.dump(self.state, handle, ensure_ascii=False)
        os.replace(temp, self.state_file)

    def belongs_to(self, server, email):
        return self.state["server"] == server and self.state["email"] == email

    def reset(self, server, email):
        self.state = {"server": server, "email": email, "since": 0, "base": {}, "held": {}, "remote_plays": {}}
        self._save()

    def base(self, kind, key):
        """The last agreed value of a record (to keep the fields this computer does not know)."""
        return self.state["base"].get(record_id(kind, key))

    def sync(self):
        # A device joining the account first takes what is already there (the account wins), then adds its own.
        if self.state["since"] == 0 and not self.state["base"]:
            self._exchange([], songkeys.Matcher(self.hooks.songs()))
        me = self.device()
        matcher = songkeys.Matcher(self.hooks.songs())
        base = dict(self.state["base"])
        held = {}
        for rid, value in self.state["held"].items():
            kind, key = rid.split("\u0000", 1)
            if not self.hooks.apply(kind, key, value, matcher):
                held[rid] = value
        current = self.hooks.export(matcher)
        current.update({rid: value for rid, value in held.items() if rid not in current})
        changes = []
        for rid, value in current.items():
            kind, key = rid.split("\u0000", 1)
            old = base.get(rid)
            if isinstance(old, dict) and isinstance(value, dict):
                value = {**old, **value}  # keep the fields only other devices know
            if old is not None and dumps(old) == dumps(value):
                continue
            changes.append({"kind": kind, "key": key, "value": value, "deleted": False})
            base[rid] = value
        for rid in list(base):
            kind, key = rid.split("\u0000", 1)
            if rid in current or not self.hooks.manages(kind, key):
                continue
            if kind == "plays" and not key.endswith(f"@{me}"):
                continue
            changes.append({"kind": kind, "key": key, "value": None, "deleted": True})
            del base[rid]
        self.state.update(base=base, held=held)
        received = self._exchange(changes, matcher)
        return {"sent": len(changes), "received": received, "held": len(self.state["held"])}

    def _exchange(self, changes, matcher):
        """Sends `changes` and applies what comes back (in pages)."""
        me = self.device()
        base, held = dict(self.state["base"]), dict(self.state["held"])
        remote_plays = dict(self.state["remote_plays"])
        since = self.state["since"]
        received = 0
        pending = changes
        for _ in range(200):
            answer = self.transport(since, pending)
            pending = []
            for item in answer.get("changes", []):
                if item.get("device") == me:
                    continue
                kind, key = item["kind"], item["key"]
                rid = record_id(kind, key)
                value = None if item.get("deleted") else item.get("value")
                received += 1
                if kind == "plays":
                    if not key.endswith(f"@{me}"):
                        if value is None:
                            remote_plays.pop(key, None)
                        else:
                            remote_plays[key] = value
                    continue
                if value is None:
                    base.pop(rid, None)
                else:
                    base[rid] = value
                held.pop(rid, None)
                if self.hooks.manages(kind, key) and not self.hooks.apply(kind, key, value, matcher) and value is not None:
                    held[rid] = value
            since = answer.get("rev", since)
            if not answer.get("more"):
                break
        self.state.update(since=since, base=base, held=held, remote_plays=remote_plays)
        self._save()
        return received
