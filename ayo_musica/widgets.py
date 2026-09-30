"""Small shared dialogs."""
from gi.repository import Adw


def confirm(window, title, body, action, text="Confirmar", destructive=False):
    dialog = Adw.AlertDialog(heading=title, body=body)
    dialog.add_response("cancel", "Cancelar")
    dialog.add_response("ok", text)
    dialog.set_close_response("cancel")
    dialog.set_default_response("cancel" if destructive else "ok")
    dialog.set_response_appearance("ok", Adw.ResponseAppearance.DESTRUCTIVE if destructive else Adw.ResponseAppearance.SUGGESTED)
    dialog.connect("response", lambda _d, response: action() if response == "ok" else None)
    dialog.present(window)
