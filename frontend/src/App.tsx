import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { useAuth } from './lib/auth';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { WebhooksPage } from './pages/WebhooksPage';
import { SimulationPage } from './pages/SimulationPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { DeadLettersPage } from './pages/DeadLettersPage';
import { HealthPage } from './pages/HealthPage';
import { ArchitecturePage } from './pages/ArchitecturePage';
import { DemoPage } from './pages/DemoPage';

export function App() {
  const { session } = useAuth();
  if (!session) return <LoginPage />;
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<DashboardPage />} />
        <Route path="payments" element={<PaymentsPage />} />
        <Route path="webhooks" element={<WebhooksPage />} />
        <Route path="simulations" element={<SimulationPage />} />
        <Route path="reconciliation" element={<ReconciliationPage />} />
        <Route path="dead-letters" element={<DeadLettersPage />} />
        <Route path="health" element={<HealthPage />} />
        <Route path="architecture" element={<ArchitecturePage />} />
        <Route path="demo" element={<DemoPage />} />
        <Route path="*" element={<DashboardPage />} />
      </Route>
    </Routes>
  );
}
