const match = /^\/(jam|friend)\/([a-f0-9]{32})\/?$/.exec(location.pathname);
if (match) {
  const [,kind,code] = match;
  document.getElementById('title').textContent = kind === 'jam' ? 'Entra. La sala te espera.' : 'Una nueva amistad, más música.';
  document.getElementById('subtitle').textContent = kind === 'jam' ? 'Abre MusicVault y escucha en compañía.' : 'Abre MusicVault para enviar tu solicitud.';
  const button = document.getElementById('open');
  button.href = `musicvault://${kind}/${code}`;
  button.hidden = false;
} else if (location.pathname !== '/') {
  document.getElementById('title').textContent = 'Esta invitación no es válida.';
  document.getElementById('subtitle').textContent = 'Pide un nuevo enlace en MusicVault.';
}
