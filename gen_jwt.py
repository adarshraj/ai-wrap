import hmac, hashlib, base64, json, time

def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

secret = 'jGwZIh1yIw1oOV7nMq4cgFKKHm9KJEQRJMcxn0wpOcE='
# base64url-decode the secret (length divisible by 4, no padding issues in Java or Python)
secret_bytes = base64.urlsafe_b64decode(secret)
header = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
now = int(time.time())
payload = b64(json.dumps({'sub': 'user1', 'iss': 'IGNORE', 'iat': now, 'exp': now + 86400}, separators=(',', ':')).encode())
sig = b64(hmac.new(secret_bytes, f'{header}.{payload}'.encode(), hashlib.sha256).digest())
print(f'{header}.{payload}.{sig}')
