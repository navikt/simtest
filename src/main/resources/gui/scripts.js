window.onload = function() {
    checkAuthorization();
};

const checkAuthorization = async () => {
    const response = await fetch('/internal/secrethello', {
        method: 'GET'
    });

    if (response.status === 401) {
        // Unauthorized
        document.getElementById('authorization-message').innerHTML =
            'Unauthorized <button id="login-button" onclick="login()">Login</button>';
        return;
    }

    document.getElementById('authorization-message').innerHTML =
        'Authorized <button id="login-button" onclick="login()">Token fetch</button>';

};

const login = () => {
    window.location.href = '/oauth2/login?redirect=/internal/gui';
};

const tokenfetch = async () => {
    document.getElementById('authorization-message').innerHTML = 'Processing...';
    try {
        const response = await fetch('/internal/tokenexchange', {
            method: 'GET'
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const text = await response.text();  // Read response body as text
        document.getElementById('authorization-message').innerHTML = text;
    } catch (error) {
        document.getElementById('authorization-message').innerHTML = `Error: ${error.message}`;
    }
}

