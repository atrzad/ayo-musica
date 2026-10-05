"""The account and cloud on the desktop: "Conta e nuvem" in the preferences, the Tudo · Neste computador · Nuvem
switch, the "Continuar de…" banner, the devices popover (like Spotify Connect) and the cloud items of song menus."""
import time

from gi.repository import Adw, GLib, Gtk

from ..cloud.service import SOURCES
from .model import duration_text


class CloudUi:
    def __init__(self, page):
        self.page = page
        self.cloud = page.cloud
        self.dismissed = ""
        # Tudo · Neste computador · Nuvem (only when signed in).
        self.source = Adw.ToggleGroup(css_classes=["round"], halign=Gtk.Align.CENTER, margin_top=6, margin_bottom=2)
        for key, title in SOURCES:
            self.source.add(Adw.Toggle(name=key, label=title))
        self.source.set_active_name(self.cloud.source)
        self.source.connect("notify::active-name", lambda group, _p: self.cloud.set_source(group.get_active_name()))
        # "Continuar de <aparelho>: <música>".
        self.banner = Adw.Banner(button_label="Continuar")
        self.banner.connect("button-clicked", lambda _b: self.continue_here())
        # Devices (header button).
        self.devices_button = Gtk.MenuButton(icon_name="video-display-symbolic", tooltip_text="Dispositivos")
        self.popover = Gtk.Popover()
        self.devices_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=10, margin_bottom=10,
                                   margin_start=10, margin_end=10, width_request=340)
        self.popover.set_child(self.devices_box)
        self.devices_button.set_popover(self.popover)
        self.popover.connect("show", lambda _p: self._devices_opened())
        self.cloud.listeners.append(self.update)
        page.window.connect("notify::is-active", lambda w, _p: w.is_active() and self.cloud.refresh_devices())
        self.update()

    # ── state ──────────────────────────────────────────────────────────────
    def update(self):
        signed = self.cloud.account.signed_in
        self.source.set_visible(signed)
        if self.source.get_active_name() != self.cloud.source:
            self.source.set_active_name(self.cloud.source)
        self.devices_button.set_visible(signed)
        offer = self.cloud.continue_offer() if signed else None
        mark = f"{offer['device']}:{offer.get('updated')}" if offer else ""
        if offer and mark != self.dismissed:
            item = (offer.get("state") or {}).get("item") or {}
            self.banner.set_title(f"Continuar de {offer.get('name') or 'outro aparelho'}: {item.get('title', '')} · "
                                  f"{item.get('artist', '')}")
            self.banner.offer = offer
            self.banner.set_revealed(True)
        else:
            self.banner.set_revealed(False)
        if self.popover.get_visible():
            self._fill_devices()

    def continue_here(self):
        offer = getattr(self.banner, "offer", None)
        if offer:
            self.dismissed = f"{offer['device']}:{offer.get('updated')}"
            if not self.cloud.continue_here(offer):
                self.page.notify("As músicas desse aparelho não estão neste computador nem na nuvem.")
        self.banner.set_revealed(False)

    # ── devices popover ────────────────────────────────────────────────────
    def _devices_opened(self):
        self._fill_devices()
        self.cloud.refresh_devices()

        def poll():
            if not self.popover.get_visible():
                return False
            self.cloud.refresh_devices()
            return True
        GLib.timeout_add_seconds(3, poll)

    def _fill_devices(self):
        while (child := self.devices_box.get_first_child()) is not None:
            self.devices_box.remove(child)
        self.devices_box.append(Gtk.Label(label="Dispositivos", xalign=0, css_classes=["title-4"]))
        if not self.cloud.devices:
            self.devices_box.append(Gtk.Label(label="Nenhum outro aparelho ainda. Abra o Ayo Música no celular com a mesma "
                                                    "conta.", wrap=True, xalign=0, css_classes=["dim-label"]))
        for device in self.cloud.devices:
            self.devices_box.append(Gtk.Separator())
            state = device.get("state") or {}
            item = state.get("item") or {}
            row = Gtk.Box(spacing=10)
            row.append(Gtk.Image(icon_name="phone-symbolic" if device.get("platform") == "android" else "computer-symbolic",
                                 pixel_size=24, css_classes=[] if device.get("online") else ["dim-label"]))
            texts = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
            texts.append(Gtk.Label(label=device.get("name") or "Outro aparelho", xalign=0, css_classes=["heading"]))
            if not device.get("online"):
                what = "Fora do ar"
            elif not item:
                what = "Nada tocando"
            else:
                what = ("Tocando " if state.get("playing") else "Pausado em ") + f"“{item.get('title', '')}” · {item.get('artist', '')}"
            texts.append(Gtk.Label(label=what, xalign=0, wrap=True, css_classes=["caption", "dim-label"]))
            if item and state.get("durationMs"):
                texts.append(Gtk.Label(label=f"{duration_text((state.get('positionMs') or 0) / 1000)} de "
                                             f"{duration_text(state['durationMs'] / 1000)}", xalign=0, css_classes=["caption"]))
            row.append(texts)
            self.devices_box.append(row)
            if device.get("online") and item:
                controls = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER)
                for icon, action, tip in (("media-skip-backward-symbolic", "previous", "Anterior lá"),
                                          ("media-playback-pause-symbolic" if state.get("playing") else
                                           "media-playback-start-symbolic", "toggle", "Tocar/pausar lá"),
                                          ("media-skip-forward-symbolic", "next", "Próxima lá")):
                    button = Gtk.Button(icon_name=icon, tooltip_text=tip, css_classes=["flat", "circular"])
                    button.connect("clicked", lambda _b, d=device, a=action: self.cloud.command(d, a))
                    controls.append(button)
                self.devices_box.append(controls)
            buttons = Gtk.Box(spacing=6)
            if item:
                here = Gtk.Button(label="Continuar aqui", css_classes=["suggested-action", "pill"])
                here.connect("clicked", lambda _b, d=device: (self.popover.popdown(), self.cloud.continue_here(d)))
                buttons.append(here)
            if device.get("online") and self.page.current_path:
                there = Gtk.Button(label="Tocar lá", css_classes=["pill"])
                there.connect("clicked", lambda _b, d=device: (self.popover.popdown(), self.cloud.play_there(d)))
                buttons.append(there)
            self.devices_box.append(buttons)


def account_page(page):
    """"Conta e nuvem" in the preferences."""
    cloud = page.cloud
    pref = Adw.PreferencesPage(title="Conta e nuvem", icon_name="weather-overcast-symbolic")
    group = Adw.PreferencesGroup(
        title="Conta", description="Entre com sua conta Google para ter a mesma biblioteca no computador e no celular: "
        "playlists, curtidas e letras sincronizadas, suas músicas na nuvem (tocar de qualquer lugar ou baixar), continuar "
        "de onde parou em outro aparelho e controlar um pelo outro.")
    pref.add(group)
    status = Adw.ActionRow(title="", subtitle="")
    group.add(status)
    sign_in = Gtk.Button(label="Entrar com o Google", css_classes=["suggested-action", "pill"], halign=Gtk.Align.START,
                         margin_top=8)
    sync = Gtk.Button(label="Sincronizar agora", css_classes=["pill"], valign=Gtk.Align.CENTER)
    upload = Adw.ActionRow(title="Músicas deste computador na nuvem")
    upload_button = Gtk.Button(label="Enviar todas", css_classes=["pill"], valign=Gtk.Align.CENTER)
    upload.add_suffix(upload_button)
    sign_out = Adw.ButtonRow(title="Sair da conta")
    status.add_suffix(sync)
    group.add(upload)
    group.add(sign_out)
    server_group = Adw.PreferencesGroup(title="Servidor", description="Onde a conta e a nuvem ficam (seu computador, "
                                                                      "publicado pelo Tailscale).")
    server_row = Adw.EntryRow(title="Endereço", text=cloud.account.server, show_apply_button=True)
    server_row.connect("apply", lambda row: (cloud.account.set_server(row.get_text()), refresh()))
    session_row = Adw.EntryRow(title="Sessão de teste (admin.js token)", show_apply_button=True)
    server_group.add(server_row)
    server_group.add(session_row)
    pref.add(server_group)
    error = Gtk.Label(wrap=True, xalign=0, css_classes=["error"], visible=False, margin_top=6)
    holder = Adw.PreferencesGroup()
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    box.append(sign_in)
    box.append(error)
    holder.add(box)
    pref.add(holder)

    def show_error(message):
        error.set_label(message or "")
        error.set_visible(bool(message))
        sign_in.set_sensitive(True)
        sign_in.set_label("Entrar com o Google")
        refresh()

    def do_sign_in(_button):
        sign_in.set_sensitive(False)
        sign_in.set_label("Abrindo o navegador…")
        cloud.sign_in_google(show_error)

    sign_in.connect("clicked", do_sign_in)
    session_row.connect("apply", lambda row: cloud.sign_in_session(row.get_text(), show_error))
    sync.connect("clicked", lambda _b: cloud.sync_now())
    upload_button.connect("clicked", lambda _b: cloud.upload([row["path"] for row in cloud.local_not_in_cloud()]))
    sign_out.connect("activated", lambda _r: (cloud.sign_out(), refresh()))

    def refresh():
        signed = cloud.account.signed_in
        holder.set_visible(not signed)
        server_group.set_visible(not signed)
        upload.set_visible(signed)
        sign_out.set_visible(signed)
        sync.set_visible(signed)
        if signed:
            status.set_title(cloud.account.name or cloud.account.email)
            last = time.strftime("%H:%M", time.localtime(cloud.status["last"])) if cloud.status["last"] else "ainda não"
            parts = [f"Última sincronização: {last}", cloud.status.get("message") or ""]
            if cloud.status.get("error"):
                parts.append(cloud.status["error"])
            status.set_subtitle(" · ".join(p for p in parts if p))
            missing = len(cloud.local_not_in_cloud())
            upload.set_subtitle("Todas já estão na nuvem." if not missing else f"{missing} ainda não estão na nuvem.")
            upload_button.set_visible(missing > 0)
        else:
            status.set_title("Você não entrou")
            status.set_subtitle(cloud.account.server)
        return False

    cloud.listeners.append(lambda: GLib.idle_add(refresh))
    refresh()
    return pref


def track_menu_section(page, tracks, paths_variant):
    """Cloud items for a song menu: download, remove the download, take out of the cloud, or send up."""
    from gi.repository import Gio
    cloud = page.cloud
    if not cloud.account.signed_in:
        return None
    section = Gio.Menu()
    in_cloud = [t for t in tracks if cloud.is_cloud(t.path)]
    local = [t for t in tracks if not cloud.is_cloud(t.path)]
    entries = []
    if in_cloud:
        not_downloaded = [t for t in in_cloud if not str(t.path).startswith(cloud.library.downloads)]
        if not_downloaded:
            entries.append(("Baixar para ouvir sem internet", "music.cloud-download"))
        if len(in_cloud) == 1 and str(in_cloud[0].path).startswith(cloud.library.downloads):
            entries.append(("Remover download", "music.cloud-remove-download"))
        if len(in_cloud) == 1:
            entries.append(("Tirar da nuvem", "music.cloud-delete"))
    if local:
        entries.append(("Enviar para a nuvem", "music.cloud-upload"))
    for title, action in entries:
        item = Gio.MenuItem.new(title, None)
        item.set_action_and_target_value(action, paths_variant)
        section.append_item(item)
    return section if entries else None
