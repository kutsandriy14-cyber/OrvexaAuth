from pathlib import Path

path = Path('/home/ubuntu/netauth-github/app/src/main/java/com/example/ui/AccountScreens.kt')
text = path.read_text()
start = text.index('    // Password recovery is intentionally handled by the existing PC web registration/recovery flow.')
end = text.index('\n}\n\n// 3. Register Name Screen', start)
text = text[:start] + text[end + 2:]
path.write_text(text)
