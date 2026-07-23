import { Component, type ErrorInfo, type ReactNode } from 'react'

type Props = { children: ReactNode; fallbackTitle?: string }
type State = { error: Error | null }

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('UI crash', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <div className="login-wrap">
          <div className="login-card">
            <h1>{this.props.fallbackTitle ?? 'Something went wrong'}</h1>
            <p className="muted">{this.state.error.message || 'The page failed to render.'}</p>
            <button
              className="btn"
              type="button"
              onClick={() => {
                try {
                  localStorage.removeItem('ekms_web_session')
                } catch {
                  /* ignore */
                }
                window.location.href = '/login'
              }}
            >
              Clear session and go to login
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
