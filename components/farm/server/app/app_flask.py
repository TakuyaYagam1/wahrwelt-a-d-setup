from factory import create_app
from log import setup_logging

setup_logging('INFO')

app = create_app()

if __name__ == '__main__':
    app.run(debug=False, port=5000, host='127.0.0.1')
