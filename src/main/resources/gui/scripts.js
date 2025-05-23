window.onload = function() {
    checkAuthorization();
};

const checkAuthorization = async () => {
    const response = await fetch('/internal/view?page=1', {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        }
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