.PHONY: run test check smoke doctor install uninstall flatpak flatpak-install

run:
	python3 -m ayo_musica

test:
	python3 -m unittest discover -s tests -v

check: test
	python3 -m compileall -q ayo_musica

smoke:
	python3 scripts/smoke.py

doctor:
	python3 scripts/doctor.py

install:
	python3 scripts/install.py

uninstall:
	python3 scripts/install.py --uninstall

# Flatpak: needs org.flatpak.Builder (flatpak install --user flathub org.flatpak.Builder).
flatpak:
	flatpak run org.flatpak.Builder --user --force-clean --install-deps-from=flathub \
		--repo=build/flatpak-repo build/flatpak packaging/flatpak/io.github.atrzad.AyoMusica.yml
	flatpak build-bundle build/flatpak-repo build/ayo-musica.flatpak io.github.atrzad.AyoMusica

flatpak-install: flatpak
	flatpak install --user --reinstall --noninteractive build/ayo-musica.flatpak
