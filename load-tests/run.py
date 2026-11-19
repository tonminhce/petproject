import json
import subprocess
import sys
import urllib.parse
import urllib.request

def main():
    data = urllib.parse.urlencode({
        'grant_type': 'password',
        'client_id': 'ecommerce-client',
        'username': 'testuser',
        'password': 'changeme',
    }).encode()

    req = urllib.request.Request(
        'http://localhost:9090/realms/ecommerce/protocol/openid-connect/token',
        data=data
    )
    with urllib.request.urlopen(req) as resp:
        token = json.loads(resp.read().decode())['access_token']

    script_name = sys.argv[1] if len(sys.argv) > 1 else 'k6-order-saga.js'
    cmd = [
        'docker', 'run', '--rm',
        '--network', 'untitled5_ecommerce-network',
        '-e', 'BASE_URL=http://gateway-service:8080',
        '-e', f'TOKEN={token}',
        '-v', f'{sys.path[0]}:/load-tests',
        'grafana/k6:latest',
        'run',
        f'/load-tests/{script_name}'
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    print(result.stdout)
    if result.stderr:
        print(result.stderr, file=sys.stderr)
    return result.returncode

if __name__ == '__main__':
    sys.exit(main())
