import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth/AuthContext'
import { ErrorBoundary } from './components/ErrorBoundary'
import { NotificationsProvider } from './notifications/NotificationsContext'
import { ThemeProvider } from './theme/ThemeContext'
import './styles.css'

const rootEl = document.getElementById('root')
if (!rootEl) {
  document.body.textContent = 'eKMS failed to start: missing #root element.'
} else {
  createRoot(rootEl).render(
    <StrictMode>
      <ErrorBoundary>
        <ThemeProvider>
          <BrowserRouter>
            <AuthProvider>
              <NotificationsProvider>
                <App />
              </NotificationsProvider>
            </AuthProvider>
          </BrowserRouter>
        </ThemeProvider>
      </ErrorBoundary>
    </StrictMode>,
  )
}
