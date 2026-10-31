(() => {
    let currentState = 'CONNECTING';
    const states = new Set(['LOCAL', 'REMOTE', 'CONNECTING', 'OFFLINE', 'AUTHENTICATION REQUIRED']);

    function drawerRoot() {
        return document.querySelector('#mainDrawer, .mainDrawer, .navDrawer, [data-testid="navigation-drawer"]');
    }

    function drawerContent(root) {
        const knownContent = root.querySelector(
            ':scope > .mainDrawer-scrollContainer, ' +
            ':scope > .navDrawer-scrollContainer, ' +
            ':scope > [class*="scrollContainer"]',
        );
        if (knownContent) return knownContent;

        // Jellyfin web versions differ in their drawer class names. Prefer the
        // existing child that contains the navigation instead of adding a new
        // sibling to the drawer's layout row.
        return Array.from(root.children).find((child) =>
            child.querySelector('a, button, [role="menuitem"], [class*="navDrawer"]'),
        ) || (root.children.length === 1 ? root.children[0] : null);
    }

    function icon(state) {
        if (state === 'LOCAL') {
            return '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M2 8.5a15 15 0 0 1 20 0M5 12a10.5 10.5 0 0 1 14 0M8.5 15.5a6 6 0 0 1 7 0M12 20h.01"/></svg>';
        }
        return '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>';
    }

    function createHeader() {
        const header = document.createElement('div');
        header.id = 'expuz-drawer-header';
        header.style.cssText = 'box-sizing:border-box;display:flex;flex:0 0 auto;flex-direction:column;align-items:stretch;width:100%;padding:calc(16px + env(safe-area-inset-top, 0px)) 16px 10px;color:inherit;';

        const appHeader = document.createElement('div');
        appHeader.style.cssText = 'display:flex;flex-direction:column;align-items:center;padding:4px 0 14px;text-align:center;';
        appHeader.innerHTML = '<img src="/native/explay-icon.png" alt="" style="display:block;width:52px;height:52px;border-radius:14px;object-fit:cover;box-shadow:0 4px 18px rgba(0,0,0,.24);"><div style="margin-top:8px;font-size:18px;font-weight:600;">Explay</div>';

        const version = window.NativeShell?.appVersion?.();
        if (version) {
            const versionLabel = document.createElement('div');
            versionLabel.textContent = `v${version}`;
            versionLabel.style.cssText = 'margin-top:3px;font-size:12px;opacity:.7;';
            appHeader.appendChild(versionLabel);
        }

        const status = document.createElement('div');
        status.id = 'expuz-connection-indicator';
        status.setAttribute('role', 'status');
        status.style.cssText = 'box-sizing:border-box;display:flex;flex:0 0 auto;align-items:center;width:100%;min-height:48px;margin:0;padding:10px 12px;border-radius:10px;background:rgba(255,255,255,.08);color:inherit;font-size:13px;font-weight:600;line-height:1.2;';

        header.appendChild(appHeader);
        header.appendChild(status);
        return header;
    }

    function render() {
        const root = drawerRoot();
        if (!root) return;
        const content = drawerContent(root);
        if (!content) return;

        let header = document.getElementById('expuz-drawer-header');
        if (!header) {
            const existingIndicator = document.getElementById('expuz-connection-indicator');
            header = createHeader();
            if (existingIndicator) {
                header.querySelector('#expuz-connection-indicator')?.replaceWith(existingIndicator);
            }
        }
        if (header.parentElement !== content) content.insertBefore(header, content.firstChild);

        const indicator = document.getElementById('expuz-connection-indicator');
        if (!indicator || indicator.dataset.state === currentState) return;

        indicator.dataset.state = currentState;
        indicator.innerHTML = `${icon(currentState)}<span style="margin-left:10px;">${currentState}</span><span aria-hidden="true" style="width:8px;height:8px;margin-left:auto;border-radius:50%;background:currentColor;opacity:.8;"></span>`;
        const svg = indicator.querySelector('svg');
        if (svg) svg.setAttribute('style', 'display:block;flex:0 0 auto;width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;');
        indicator.setAttribute('aria-label', `Connection ${currentState}`);
    }

    window.ExpuzConnectionIndicator = {
        update(state) {
            currentState = states.has(state) ? state : 'OFFLINE';
            render();
        },
    };

    const observer = new MutationObserver(render);
    if (document.documentElement) observer.observe(document.documentElement, { childList: true, subtree: true });
    render();
})();
