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

    // If authorized, optionally hide the message
    document.getElementById('authorization-message').textContent = '';
};

const login = () => {
    window.location.href = '/oauth2/login?redirect=/internal/gui';
};