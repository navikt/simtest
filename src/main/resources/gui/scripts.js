const authorizationMessage = document.getElementById('authorization-message');
const exchangeStatus = document.getElementById('exchange-status');
const tokenResult = document.getElementById('token-result');
const nameButton = document.getElementById('exchange-name');
const appName = document.getElementById('app-name');
const copyButton = document.getElementById('copy-token');
let generation = 0;

function clearToken() {
    generation++;
    tokenResult.value = '';
    tokenResult.hidden = true;
    copyButton.hidden = true;
}

function setButtonsDisabled(disabled) {
    nameButton.disabled = disabled;
}

function showLogin(message) {
    clearToken();
    setButtonsDisabled(true);
    authorizationMessage.replaceChildren();
    const text = document.createElement('p');
    text.textContent = message;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = 'Logg inn med Nav';
    button.addEventListener('click', () => {
        window.location.href = '/oauth2/login?redirect=/internal/gui';
    });
    authorizationMessage.append(text, button);
}

async function checkAuthorization() {
    authorizationMessage.textContent = 'Sjekker innlogging …';
    try {
        const response = await fetch('/internal/secrethello', { cache: 'no-store' });
        if (response.status === 401) {
            showLogin('Du må logge inn med en Nav-bruker for å hente et token.');
        } else if (response.ok) {
            const name = (await response.text()).trim();
            authorizationMessage.textContent = `${name || 'Du'} er logget inn i simtest.`;
            setButtonsDisabled(false);
        } else {
            authorizationMessage.textContent = 'Kunne ikke kontrollere innloggingen. Last siden på nytt.';
        }
    } catch (_) {
        authorizationMessage.textContent = 'Kunne ikke kontrollere innloggingen. Last siden på nytt.';
    }
}

async function exchange() {
    clearToken();
    const currentGeneration = generation;
    setButtonsDisabled(true);
    exchangeStatus.textContent = 'Henter token …';
    try {
        const response = await fetch('/internal/tokenexchange-name', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: appName.value,
            cache: 'no-store'
        });
        if (currentGeneration !== generation) return;
        if (response.status === 401) {
            showLogin('Innloggingen er utløpt. Logg inn på nytt.');
            exchangeStatus.textContent = '';
            return;
        }
        if (response.status === 403) {
            exchangeStatus.textContent = 'Forespørselen ble avvist. Kontroller innloggingen og prøv igjen.';
            return;
        }
        if (response.status === 400) {
            exchangeStatus.textContent = 'Ugyldig appnavn. Bruk dev-gcp:namespace:appnavn eller dev-fss:namespace:appnavn.';
            return;
        }
        if (!response.ok) {
            exchangeStatus.textContent = 'Kunne ikke hente token. Prøv igjen senere.';
            return;
        }
        const token = await response.text();
        if (currentGeneration !== generation) return;
        if (!token) {
            exchangeStatus.textContent = 'Tjenesten returnerte ikke et token. Prøv igjen senere.';
            return;
        }
        tokenResult.value = token;
        tokenResult.hidden = false;
        copyButton.hidden = false;
        exchangeStatus.textContent = 'Tokenet er klart.';
    } catch (_) {
        if (currentGeneration === generation) {
            exchangeStatus.textContent = 'Kunne ikke hente token. Prøv igjen senere.';
        }
    } finally {
        if (authorizationMessage.querySelector('button') === null) {
            setButtonsDisabled(false);
        }
    }
}

nameButton.addEventListener('click', exchange);
appName.addEventListener('input', () => {
    clearToken();
    exchangeStatus.textContent = '';
});
copyButton.addEventListener('click', async () => {
    if (tokenResult.hidden || !tokenResult.value) return;
    try {
        await navigator.clipboard.writeText(tokenResult.value);
        exchangeStatus.textContent = 'Tokenet er kopiert. Lim det inn i Postman.';
    } catch (_) {
        exchangeStatus.textContent = 'Kunne ikke kopiere automatisk. Marker tokenet og kopier det manuelt.';
        tokenResult.focus();
        tokenResult.select();
    }
});
window.addEventListener('pagehide', clearToken);
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        clearToken();
        exchangeStatus.textContent = '';
    }
});

checkAuthorization();
